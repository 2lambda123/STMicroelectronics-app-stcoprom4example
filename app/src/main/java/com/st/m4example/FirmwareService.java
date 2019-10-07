package com.st.m4example;

import android.app.Service;
import android.content.Intent;
import android.copro.CoproManager;
import android.copro.CoproSerialPort;
import android.copro.FirmwareInfo;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

public class FirmwareService extends Service {

    // Received actions and extras
    public static final String  ACTION_START = "st.com.stm4example.action.START";
    public static final String  EXTRA_FW_NAME = "st.com.stm4example.extra.FW_NAME";

    public static final String  ACTION_STOP = "st.com.stm4example.action.STOP";

    public static final String  ACTION_SEND_COMMAND = "st.com.stm4example.action.SEND_COMMAND";
    public static final String  EXTRA_FW_COMMAND = "st.com.stm4example.extra.FW_COMMAND";

    // Sent actions and extras
    public static final String ACTION_FW_STATUS = "st.com.stm4example.action.FW_STATUS";
    public static final String EXTRA_FW_STATUS = "st.com.stm4example.extra.FW_STATUS";

    public static final String FW_STARTED = "STARTED";
    public static final String FW_STOPPED = "STOPPED";
    public static final String FW_ERROR = "ERROR";

    public static final String ACTION_UPDATE = "st.com.stm4example.action.UPDATE";
    public static final String EXTRA_UPDATE = "st.com.stm4example.extra.UPDATE";

    private static final String LOG_TAG = FirmwareService.class.getSimpleName();

    private CoproManager mCoproManager;
    private volatile CoproSerialPort mCoproSerialPort = null;
    private FirmwareInfo mFw;

    private static readFwThread mReadFwThread = null;
    private static final Semaphore mSemaphore = new Semaphore(1,true);

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopFirmware();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent.getAction() == null)
            return super.onStartCommand(intent, flags, startId);

        switch (intent.getAction()) {
            case ACTION_START:
                String firmware = intent.getStringExtra(EXTRA_FW_NAME);
                handleActionStartFirmware(firmware);
                break;
            case ACTION_STOP:
                handleActionStopFirmware();
            case ACTION_SEND_COMMAND:
                String command = intent.getStringExtra(EXTRA_FW_COMMAND);
                handleActionSendCommand(command);
            default:
                return super.onStartCommand(intent, flags, startId);
        }
        return START_STICKY;
    }

    /**
     * Handle action StartFirmware in the provided background thread with the provided
     * parameters.
     */
    private void handleActionStartFirmware(String firmware) {
        if (startFirmware(firmware)) {
            mReadFwThread = new readFwThread();     // start a thred to read data from M4 FW
            mReadFwThread.start();
        }
    }

    /**
     * Handle action StopFirmware in the provided background thread with the provided
     * parameters.
     */
    private void handleActionStopFirmware() {
        stopFirmware();
        Intent updateIntent = new Intent(ACTION_FW_STATUS);
        updateIntent.putExtra(EXTRA_FW_STATUS, FW_STOPPED);
        sendBroadcast(updateIntent);
    }

    /**
     * Handle action SendCommand in the provided background thread with the provided
     * parameters.
     */
    private void handleActionSendCommand(String command) {
        if(mCoproSerialPort != null){
            mReadFwThread.sendString(command);
        }
    }

    private void stopFirmware() {
        if (mReadFwThread != null) {
            mReadFwThread.cancel();
            // use semaphore to wait for async task end
            try {
                mSemaphore.acquire();
            } catch (InterruptedException e) {
                Log.e(LOG_TAG,"Error: can't acquire required semaphore");
                e.printStackTrace();
            }
            mSemaphore.release();
        } else {
            // Safe debug (case where the firmware has not been stopped while service has been killed)
            if ((mCoproManager != null) && (mFw != null)) {
                if (mCoproManager.isFirmwareRunning(mFw.getId())) {
                    if (mCoproSerialPort == null) {
                        mCoproSerialPort = mCoproManager.getSerialPort();
                    }
                    mCoproSerialPort.close();
                    mCoproSerialPort = null;
                }
            }
        }
        if ((mCoproManager != null) && (mFw != null)) {
            if (mCoproManager.isFirmwareRunning(mFw.getId())) {
                mCoproManager.stopFirmware();   // do not M4 FW running when application is killed
                Log.d(LOG_TAG, "mCoproManager.stopFirmware OK");
            }
        }
    }

    private Boolean startFirmware(String firmware) {
        mCoproManager = CoproManager.getInstance();
        if(mCoproManager != null) {
            Intent updateIntent = new Intent(ACTION_FW_STATUS);
            mFw = mCoproManager.getFirmwareByName(firmware);
            if (mFw != null) {
                if (mCoproManager.isFirmwareRunning(mFw.getId())) {
                    // force restart (safe debug)
                    stopFirmware();
                }
                mCoproManager.startFirmware(mFw.getId());
                if(mCoproManager.isFirmwareRunning(mFw.getId())) {
                    Log.d(LOG_TAG, firmware + " firmware start OK");
                    updateIntent.putExtra(EXTRA_FW_STATUS, FW_STARTED);
                    sendBroadcast(updateIntent);
                } else {
                    Log.d(LOG_TAG, firmware + " firmware start ERROR !!!");
                    updateIntent.putExtra(EXTRA_FW_STATUS, FW_ERROR);
                    sendBroadcast(updateIntent);
                    return false;
                }
            } else {
                Log.d(LOG_TAG, firmware + " firmware not found");
                updateIntent.putExtra(EXTRA_FW_STATUS, FW_ERROR);
                sendBroadcast(updateIntent);
                return false;
            }
        } else {
            return false;
        }

        mCoproSerialPort = mCoproManager.getSerialPort();
        if(mCoproSerialPort != null){
            try {
                mCoproSerialPort.open(1);
            }catch(RemoteException e ){
                e.printStackTrace();
                Log.e(LOG_TAG, "Exception mCoproSerialPort.open");
                return false;
            }
            Log.d(LOG_TAG, "mCoproSerialPort.open OK");
            return true;
        } else {
            return false;
        }
    }

    private class readFwThread extends Thread {
        private AtomicBoolean mWorkOnGoing = new AtomicBoolean(true);

        public void run() {
            String recStr;
            // acquire semaphore to block any activity stop while this thread is not terminated
            try {
                mSemaphore.acquire();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            while (mWorkOnGoing.get()) {
                recStr = mCoproSerialPort.read();
                if (recStr != null) { // to avoid crash at application init
                    if (!recStr.isEmpty()) {
                        Intent updateIntent = new Intent(ACTION_UPDATE);
                        updateIntent.putExtra(EXTRA_UPDATE, recStr);
                        sendBroadcast(updateIntent);
                    }
                }
                try {
                    Thread.sleep(50); // giving time (50ms) to UI
                } catch (InterruptedException e) {
                    // Auto-generated catch block
                    e.printStackTrace();
                }
            }
            mCoproSerialPort.close();
            Log.d(LOG_TAG, "mCoproSerialPort.close OK");
            mCoproSerialPort = null;
            // release semaphore to allow finalizing activity stop
            mSemaphore.release();
        }

        void sendString(String str) {
            mCoproSerialPort.write(str);
        }

        void cancel() {
            mWorkOnGoing.set(false);
        }
    }
}
