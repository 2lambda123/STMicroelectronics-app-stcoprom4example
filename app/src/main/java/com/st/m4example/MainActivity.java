package com.st.m4example;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.os.Bundle;
import android.security.keystore.KeyProperties;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.GridLabelRenderer;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

public class MainActivity extends AppCompatActivity {

    private static final String LOG_TAG = MainActivity.class.getSimpleName();

    // Firmware details
    private static final String FW_NAME = "copro_m4example.elf";
    private static final String FW_COMMAND_CRC = "Star"; // part used to calculate CRC
    private static final String FW_COMMAND = FW_COMMAND_CRC + " 12345678\n";

    private Button mButtonStart;
    private TextView mDebugLog;
    private GraphView mGraph;

    private static LineGraphSeries<DataPoint> mSeries;

    private static Long mDigestCRC;
    private static byte[] bHASHValues = new byte[32];
    private static byte[] bcADCValues = new byte[64];
    private static byte[] CRYPValues = new byte[64];

    private static byte[] encryptedData = new byte[64];
    private static byte[] decryptedData = new byte[64];
    private static byte[] digestData = new byte[32];

    private static byte[] key = { (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00 };

    private static byte[] clearData = { (byte) 0x1f, (byte) 0x24, (byte) 0x2b, (byte) 0x32,
            (byte) 0x3a, (byte) 0x43, (byte) 0x4d, (byte) 0x57,
            (byte) 0x61, (byte) 0x6c, (byte) 0x77, (byte) 0x82,
            (byte) 0x8e, (byte) 0x99, (byte) 0xa4, (byte) 0xae,
            (byte) 0xb8, (byte) 0xc2, (byte) 0xcb, (byte) 0xd4,
            (byte) 0xdc, (byte) 0xe3, (byte) 0xe8, (byte) 0xed,
            (byte) 0xf1, (byte) 0xf4, (byte) 0xf6, (byte) 0xf7,
            (byte) 0xf7, (byte) 0xf5, (byte) 0xf3, (byte) 0xf0,
            (byte) 0xeb, (byte) 0xe6, (byte) 0xdf, (byte) 0xd8,
            (byte) 0xd0, (byte) 0xc7, (byte) 0xbd, (byte) 0xb3,
            (byte) 0xa9, (byte) 0x9e, (byte) 0x93, (byte) 0x88,
            (byte) 0x7d, (byte) 0x72, (byte) 0x67, (byte) 0x5c,
            (byte) 0x52, (byte) 0x48, (byte) 0x3f, (byte) 0x36,
            (byte) 0x2e, (byte) 0x27, (byte) 0x21, (byte) 0x1c,
            (byte) 0x18, (byte) 0x15, (byte) 0x13, (byte) 0x13,
            (byte) 0x13, (byte) 0x14, (byte) 0x17, (byte) 0x1a };

    private final BroadcastReceiver mainReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (FirmwareService.ACTION_FW_STATUS.equals(action)) {
                String status = intent.getStringExtra(FirmwareService.EXTRA_FW_STATUS);
                if (FirmwareService.FW_STARTED.equals(status)) {
                    mButtonStart.setVisibility(View.VISIBLE);
                    mButtonStart.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) {
                            mButtonStart.setVisibility(View.INVISIBLE);
                            mDebugLog.setText("M4 resources used: TIM2 TIM7 DAC1 ADC1 DMA2 CRC2 HASH2 CRY2\n");
                            Intent intent = new Intent(MainActivity.this, FirmwareService.class);
                            intent.setAction(FirmwareService.ACTION_SEND_COMMAND);
                            intent.putExtra(FirmwareService.EXTRA_FW_COMMAND, FW_COMMAND);
                            startService(intent);
                        }
                    });
                } else if (FirmwareService.FW_STOPPED.equals(status)) {
                    mButtonStart.setVisibility(View.INVISIBLE);
                } else if (FirmwareService.FW_ERROR.equals(status)) {
                    mButtonStart.setVisibility(View.INVISIBLE);
                    showDebugUI();
                }
            } else if (FirmwareService.ACTION_UPDATE.equals(action)) {
                updateUI(intent.getStringExtra(FirmwareService.EXTRA_UPDATE));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mButtonStart = findViewById(R.id.button_start);
        mDebugLog = findViewById(R.id.debug_log);
        mGraph = findViewById(R.id.graph1);

        IntentFilter filter = new IntentFilter();
        filter.addAction(FirmwareService.ACTION_FW_STATUS);
        filter.addAction(FirmwareService.ACTION_UPDATE);
        registerReceiver(mainReceiver, filter);
    }

    @Override
    protected void onStart() {
        super.onStart();

        initCrc();
        initGraph();

        Intent intent = new Intent(MainActivity.this, FirmwareService.class);
        intent.setAction(FirmwareService.ACTION_START);
        intent.putExtra(FirmwareService.EXTRA_FW_NAME, FW_NAME);
        startService(intent);
    }

    private void initCrc() {
        // compute referenced CRC32
        CRC32 crc1 = new CRC32();
        crc1.update(FW_COMMAND_CRC.getBytes());
        mDigestCRC = 0xFFFFFFFF - crc1.getValue();  // complement to 1 is needed
    }

    private void initGraph() {
        mSeries = new LineGraphSeries<>();
        mSeries.setColor(ContextCompat.getColor(this, R.color.colorAccent));
        mSeries.setThickness(4);
        mGraph.addSeries(mSeries);
        mGraph.getViewport().setMinY(0);
        mGraph.getViewport().setMaxY(127);
        mGraph.getViewport().setMinX(0);
        mGraph.getViewport().setMaxX(63);
        GridLabelRenderer gridLabel1 = mGraph.getGridLabelRenderer();
        gridLabel1.setHorizontalAxisTitle("Index");
        gridLabel1.setHorizontalAxisTitleColor(ContextCompat.getColor(this, R.color.colorPrimaryDark));
        gridLabel1.setVerticalAxisTitle("ADC value");
        gridLabel1.setVerticalAxisTitleColor(ContextCompat.getColor(this, R.color.colorPrimaryDark));
        gridLabel1.setGridColor(ContextCompat.getColor(this, R.color.colorPrimaryDark));
        mGraph.getViewport().setYAxisBoundsManual(true);
        mGraph.getViewport().setXAxisBoundsManual(true);
        mGraph.getGridLabelRenderer().setHorizontalLabelsVisible(true);
        mGraph.getLegendRenderer().setVisible(false);
    }

    @Override
    protected void onStop() {
        super.onStop();
        Intent intent = new Intent(MainActivity.this, FirmwareService.class);
        intent.setAction(FirmwareService.ACTION_STOP);
        startService(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mainReceiver);
    }

    private void updateUI(String data) {
        int nbValues, index;
        Log.d(LOG_TAG, "handleMessage : " + data);
        if (data != null) {
            Matcher matcher;
            Pattern rg = Pattern.compile("[0-9a-fA-F]+");

            if (data.contains("CRC=")) {
                // treat M4 CRC
                index = data.indexOf("CRC=");
                if (index > 0) {
                    matcher = rg.matcher(data.substring(index + 4));    // search after "CRC="
                    if (matcher.find()) {
                        int res = checkCRC32(matcher.group());
                        if (res == 0) {
                            mDebugLog.append("CRC32 comparison success\n");
                        } else {
                            mDebugLog.append("CRC32 comparison failure: bad value\n");
                        }
                    } else {
                        mDebugLog.append("CRC32 comparison failure: string not found\n");
                    }
                } else {
                    mDebugLog.append("CRC32 comparison failure: CRC not found\n");
                }
            }
            if (data.contains("ADC=")) {
                // treat ADC
                index = data.indexOf("ADC=");
                if (index > 0) {
                    int mNbClearADCValues = 0;
                    matcher = rg.matcher(data.substring(index + 4));    // search after "ADC="
                    while (matcher.find()) {
                        String tmpStr = matcher.group();
                        bcADCValues[mNbClearADCValues++] = (byte) (Integer.parseInt(tmpStr, 16) & 0xFF);
                        //clearADCValues[mNbClearADCValues++] = Integer.parseInt(tmpStr, 16);
                        if (mNbClearADCValues >= 64) break;     //avoid array overflow
                    }
                    updateGraph(mNbClearADCValues, bcADCValues);
                }
            }
            if (data.contains("HASH=")) {
                // treat HASH
                index = data.indexOf("HASH=");
                if (index > 0) {
                    nbValues = 0;
                    matcher = rg.matcher(data.substring(index + 5));    // search after "HASH="
                    while (matcher.find()) {
                        String tmpStr = matcher.group();
                        bHASHValues[nbValues++] = (byte) (Integer.parseInt(tmpStr, 16) & 0xFF);
                        if (nbValues >= 32) break;     //avoid array overflow
                    }
                }

                MessageDigest digest;
                try {
                    digest = MessageDigest.getInstance("SHA-256");
                    digest.update(bcADCValues);
                    digestData = digest.digest();

                    if (CompareValues(bHASHValues, digestData, 32)) {
                        mDebugLog.append("SHA-256 comparison success\n");
                    } else {
                        mDebugLog.append("SHA-256 comparison failure\n");
                    }
                } catch (NoSuchAlgorithmException e1) {
                    // Auto-generated catch block
                    e1.printStackTrace();
                    mDebugLog.append("SHA-256 algorithm failure\n");
                }
            }
            if (data.contains("ENCR=")) {
                // treat CRYP
                index = data.indexOf("ENCR=");
                if (index > 0) {
                    nbValues = 0;
                    matcher = rg.matcher(data.substring(index + 5));    // search after "ENCR="
                    while (matcher.find()) {
                        String tmpStr = matcher.group();
                        int tVal = Integer.parseInt(tmpStr, 16);
                        CRYPValues[nbValues++] = (byte) (tVal & 0xFF);
                        //CRYPValues[nbValues++] = Byte.parseByte(tmpStr, 16);  // => java.lang.NumberFormatException: Value out of range. Value:"a9" Radix:16
                        if (nbValues >= 64) break;     //avoid array overflow
                    }
                    if (nbValues == 64) {

                        try {
                            encryptedData = encrypt(key, bcADCValues);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }

                        if (CompareValues(encryptedData, CRYPValues, 64)) {
                            mDebugLog.append("AES-ECB comparison success\n");
                        } else {
                            mDebugLog.append("AES-ECB comparison failure\n");
                        }
                        // This is the final data received, can start another measure
                        mButtonStart.setVisibility(View.VISIBLE);
    /*
                            // => javax.crypto.BadPaddingException: pad block corrupted
                            try {
                                decryptedADCValues = decrypt(key, CRYPValues);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
    */
                    }
                }
            }
        }
    }

    private static byte[] encrypt(byte[] raw, byte[] clear) throws Exception {
        SecretKeySpec skeySpec = new SecretKeySpec(raw, KeyProperties.KEY_ALGORITHM_AES);
        // use default AES-ECB (just for demonstration purpose, not secure) - remove warning
        @SuppressLint("GetInstance") Cipher cipher = Cipher.getInstance(KeyProperties.KEY_ALGORITHM_AES);
        cipher.init(Cipher.ENCRYPT_MODE, skeySpec);
        return cipher.doFinal(clear);
    }

    private static byte[] decrypt(byte[] raw, byte[] encrypted) throws Exception {
        SecretKeySpec skeySpec = new SecretKeySpec(raw, KeyProperties.KEY_ALGORITHM_AES);
        // use default AES-ECB (just for demonstration purpose, not secure) - remove warning
        @SuppressLint("GetInstance") Cipher cipher = Cipher.getInstance(KeyProperties.KEY_ALGORITHM_AES);
        cipher.init(Cipher.DECRYPT_MODE, skeySpec);
        return cipher.doFinal(encrypted);
    }

    private static void updateGraph(int nbS, byte[] data) {
        int i;
        DataPoint[] values = new DataPoint[nbS];
        DataPoint v1;
        for (i = 0; i < nbS; i++) {
            v1 = new DataPoint(i, data[i]);
            values[i] = v1;
        }
        mSeries.resetData(values);
    }

    private static int checkCRC32(String crc32Str) {
        int mCRC = Integer.parseUnsignedInt(crc32Str, 16);
        int dig = mDigestCRC.intValue();
        // compare M4 and A7 compute CRC
        return Integer.compareUnsigned(dig, mCRC);
    }

    private static boolean CompareValues(byte[] in1, byte[] in2, int size) {
        for (int i=0; i<size; i++) {
            if (in1[i] != in2[i]) {
                return false;
            }
        }
        return true;
    }

    private void showDebugUI() {
        mDebugLog.setText("Testing...\n");

        // M4 crc=96160ad5
        if (checkCRC32("96160AD5") == 0) {
            mDebugLog.append("CRC32 comparison success\n");
        } else {
            mDebugLog.append("CRC32 comparison failure\n");
        }

        StringBuilder encryptStringBuilder = new StringBuilder();
        encryptStringBuilder.append("Clear=");
        for (int i=0; i<64; i++) {
            String str = String.format(Locale.FRENCH, "%02x ", clearData[i]);
            encryptStringBuilder.append(str);
        }
        encryptStringBuilder.append("\n");
        mDebugLog.append(encryptStringBuilder.toString());

        try {
            encryptedData = encrypt(key, clearData);
        } catch (Exception e) {
            e.printStackTrace();
        }

        encryptStringBuilder = new StringBuilder();
        encryptStringBuilder.append("Encrypted=");
        for (int i=0; i<64; i++) {
            String str = String.format(Locale.FRENCH, "%02x ", encryptedData[i]);
            encryptStringBuilder.append(str);
        }
        encryptStringBuilder.append("\n");
        mDebugLog.append(encryptStringBuilder.toString());

        try {
            decryptedData = decrypt(key,encryptedData);
        } catch (Exception e) {
            e.printStackTrace();
        }

        StringBuilder decryptStringBuilder = new StringBuilder();
        decryptStringBuilder.append("Decrypted=");
        for (int i=0; i<64; i++) {
            String str = String.format(Locale.FRENCH, "%02x ", decryptedData[i]);
            decryptStringBuilder.append(str);
        }
        decryptStringBuilder.append("\n");
        mDebugLog.append(decryptStringBuilder.toString());

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
            digest.update(clearData);
            //digest.update(inpHASH.getBytes());
            digestData = digest.digest();

            encryptStringBuilder = new StringBuilder();
            encryptStringBuilder.append("HASH256=");
            for (int i=0; i<32; i++) {
                String str = String.format(Locale.FRENCH, "%02x ", digestData[i]);
                encryptStringBuilder.append(str);
            }
            encryptStringBuilder.append("\n");
            mDebugLog.append(encryptStringBuilder.toString());
        } catch (NoSuchAlgorithmException e1) {
            // Auto-generated catch block
            e1.printStackTrace();
            encryptStringBuilder.append("HASH256 ERROR !!!\n");
        }
    }
}
