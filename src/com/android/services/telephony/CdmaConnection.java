/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.services.telephony;

import android.os.Handler;
import android.os.Message;

import android.provider.Settings;
import android.telecom.PhoneCapabilities;
import android.telephony.DisconnectCause;
import android.telephony.PhoneNumberUtils;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.imsphone.ImsPhoneConnection;
import com.android.internal.telephony.Phone;
import com.android.phone.Constants;

import java.util.LinkedList;
import java.util.Queue;

/**
 * Manages a single phone call handled by CDMA.
 */
final class CdmaConnection extends TelephonyConnection {

    private static final int MSG_CALL_WAITING_MISSED = 1;
    private static final int MSG_DTMF_SEND_CONFIRMATION = 2;
    private static final int MSG_CDMA_LINE_CONTROL_INFO_REC = 3;
    private static final int TIMEOUT_CALL_WAITING_MILLIS = 20 * 1000;

    private final Handler mHandler = new Handler() {

        /** ${inheritDoc} */
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_CALL_WAITING_MISSED:
                    hangupCallWaiting(DisconnectCause.INCOMING_MISSED);
                    break;
                case MSG_DTMF_SEND_CONFIRMATION:
                    handleBurstDtmfConfirmation();
                    break;
                case MSG_CDMA_LINE_CONTROL_INFO_REC:
                    onCdmaLineControlInfoRec();
                    break;
                default:
                    break;
            }
        }

    };

    /**
     * {@code True} if the CDMA connection should allow mute.
     */
    private final boolean mAllowMute;
    // Queue of pending short-DTMF characters.
    private final Queue<Character> mDtmfQueue = new LinkedList<>();
    private final EmergencyTonePlayer mEmergencyTonePlayer;

    // Indicates that the DTMF confirmation from telephony is pending.
    private boolean mDtmfBurstConfirmationPending = false;
    private boolean mIsCallWaiting;
    private boolean mConnectionTimeReset = false;

    CdmaConnection(
            Connection connection,
            EmergencyTonePlayer emergencyTonePlayer,
            boolean allowMute,
            boolean isOutgoing) {
        super(connection);
        mEmergencyTonePlayer = emergencyTonePlayer;
        mAllowMute = allowMute;
        mIsOutgoing = isOutgoing;
        mIsCallWaiting = connection != null && connection.getState() == Call.State.WAITING;
        boolean isImsCall = getOriginalConnection() instanceof ImsPhoneConnection;
        // Start call waiting timer for CDMA waiting call.
        if (mIsCallWaiting && !isImsCall) {
            startCallWaitingTimer();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onPlayDtmfTone(char digit) {
        if (useBurstDtmf()) {
            Log.i(this, "sending dtmf digit as burst");
            if (getPhone() != null) {
                // if LCH is on for this connection, that means that, this DTMF request is to play SCH
                // tones at far end. Hence use # for playing SCH tones over CDMA.
                if (TelephonyConnectionService.isLchActive(getPhone().getPhoneId())) {
                    digit = '#';
                }
            }
            sendShortDtmfToNetwork(digit);
        } else {
            Log.i(this, "sending dtmf digit directly");
            getPhone().startDtmf(digit);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onStopDtmfTone() {
        if (!useBurstDtmf()) {
            getPhone().stopDtmf();
        }
    }

    @Override
    public void onReject() {
        Connection connection = getOriginalConnection();
        if (connection != null) {
            switch (connection.getState()) {
                case INCOMING:
                    // Normal ringing calls are handled the generic way.
                    super.onReject();
                    break;
                case WAITING:
                    hangupCallWaiting(DisconnectCause.INCOMING_REJECTED);
                    break;
                default:
                    Log.e(this, new Exception(), "Rejecting a non-ringing call");
                    // might as well hang this up, too.
                    super.onReject();
                    break;
            }
        }
    }

    @Override
    public void onAnswer() {
        mHandler.removeMessages(MSG_CALL_WAITING_MISSED);
        super.onAnswer();
    }

    /**
     * Clones the current {@link CdmaConnection}.
     * <p>
     * Listeners are not copied to the new instance.
     *
     * @return The cloned connection.
     */
    @Override
    public TelephonyConnection cloneConnection() {
        CdmaConnection cdmaConnection = new CdmaConnection(getOriginalConnection(),
                mEmergencyTonePlayer, mAllowMute, mIsOutgoing);
        return cdmaConnection;
    }

    @Override
    public void onStateChanged(int state) {
        Connection originalConnection = getOriginalConnection();
        mIsCallWaiting = originalConnection != null &&
                originalConnection.getState() == Call.State.WAITING;

        if (state == android.telecom.Connection.STATE_DIALING) {
            if (isEmergency() && mEmergencyTonePlayer != null) {
                mEmergencyTonePlayer.start();
            }
        } else {
            // No need to check if it is an emergency call, since it is a no-op if it isn't started.
            if (mEmergencyTonePlayer != null) {
                mEmergencyTonePlayer.stop();
            }
        }

        super.onStateChanged(state);
    }

    @Override
    void setOriginalConnection(com.android.internal.telephony.Connection originalConnection) {
        super.setOriginalConnection(originalConnection);
        getPhone().registerForLineControlInfo(mHandler, MSG_CDMA_LINE_CONTROL_INFO_REC, null);
    }

    @Override
    protected void close() {
        super.close();
        if (getPhone() != null) {
            getPhone().unregisterForLineControlInfo(mHandler);
        }
        mConnectionTimeReset = false;
    }

    private void onCdmaLineControlInfoRec() {
        if (mOriginalConnection != null && mOriginalConnection.getState() == Call.State.ACTIVE) {
            if (mOriginalConnection.getDurationMillis() > 0 &&
                    !mOriginalConnection.isIncoming() && !mConnectionTimeReset) {
                mConnectionTimeReset = true;
                resetCdmaConnectionTime();
            }
        }
    }

    @Override
    protected int buildCallCapabilities() {
        int capabilities = super.buildCallCapabilities();
        if (mAllowMute) {
            capabilities |= PhoneCapabilities.MUTE;
        }
        return capabilities;
    }

    @Override
    public void performConference(TelephonyConnection otherConnection) {
        if (isImsConnection()) {
            super.performConference(otherConnection);
        } else {
            Log.w(this, "Non-IMS CDMA Connection attempted to call performConference.");
        }
    }

    void forceAsDialing(boolean isDialing) {
        if (isDialing) {
            setDialing();
        } else {
            updateState();
        }
    }

    boolean isCallWaiting() {
        return mIsCallWaiting;
    }

    /**
     * We do not get much in the way of confirmation for Cdma call waiting calls. There is no
     * indication that a rejected call succeeded, a call waiting call has stopped. Instead we
     * simulate this for the user. We allow TIMEOUT_CALL_WAITING_MILLIS milliseconds before we
     * assume that the call was missed and reject it ourselves. reject the call automatically.
     */
    private void startCallWaitingTimer() {
        mHandler.sendEmptyMessageDelayed(MSG_CALL_WAITING_MISSED, TIMEOUT_CALL_WAITING_MILLIS);
    }

    private void hangupCallWaiting(int telephonyDisconnectCause) {
        Connection originalConnection = getOriginalConnection();
        if (originalConnection != null) {
            try {
                originalConnection.hangup();
            } catch (CallStateException e) {
                Log.e(this, e, "Failed to hangup call waiting call");
            }
            setDisconnected(DisconnectCauseUtil.toTelecomDisconnectCause(telephonyDisconnectCause));
        }
    }

    /**
     * Read the settings to determine which type of DTMF method this CDMA phone calls.
     */
    private boolean useBurstDtmf() {
        if (isImsConnection()) {
            Log.d(this,"in ims call, return false");
            return false;
        }
        int dtmfTypeSetting = Settings.System.getInt(
                getPhone().getContext().getContentResolver(),
                Settings.System.DTMF_TONE_TYPE_WHEN_DIALING,
                Constants.DTMF_TONE_TYPE_NORMAL);
        return dtmfTypeSetting == Constants.DTMF_TONE_TYPE_NORMAL;
    }

    private void sendShortDtmfToNetwork(char digit) {
        synchronized(mDtmfQueue) {
            if (mDtmfBurstConfirmationPending) {
                mDtmfQueue.add(new Character(digit));
            } else {
                sendBurstDtmfStringLocked(Character.toString(digit));
            }
        }
    }

    private void sendBurstDtmfStringLocked(String dtmfString) {
        getPhone().sendBurstDtmf(
                dtmfString, 0, 0, mHandler.obtainMessage(MSG_DTMF_SEND_CONFIRMATION));
        mDtmfBurstConfirmationPending = true;
    }

    private void handleBurstDtmfConfirmation() {
        String dtmfDigits = null;
        synchronized(mDtmfQueue) {
            mDtmfBurstConfirmationPending = false;
            if (!mDtmfQueue.isEmpty()) {
                StringBuilder builder = new StringBuilder(mDtmfQueue.size());
                while (!mDtmfQueue.isEmpty()) {
                    builder.append(mDtmfQueue.poll());
                }
                dtmfDigits = builder.toString();

                // It would be nice to log the digit, but since DTMF digits can be passwords
                // to things, or other secure account numbers, we want to keep it away from
                // the logs.
                Log.i(this, "%d dtmf character[s] removed from the queue", dtmfDigits.length());
            }
            if (dtmfDigits != null) {
                sendBurstDtmfStringLocked(dtmfDigits);
            }
        }
    }

    private boolean isEmergency() {
        Phone phone = getPhone();
        return phone != null &&
                PhoneNumberUtils.isLocalEmergencyNumber(
                    phone.getContext(), getAddress().getSchemeSpecificPart());
    }
}
