package edu.duke.cs590.kunaal.microcdn;

import android.app.job.JobService;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;

import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;

import android.content.Context;
import android.os.Handler;
import android.os.ParcelUuid;
import android.util.Log;

import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import android.support.v7.app.AppCompatActivity;

import android.os.Bundle;
import android.os.AsyncTask;

import java.io.BufferedInputStream;
import java.io.InputStream;

import java.net.HttpURLConnection;
import java.net.URL;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final String resourceURL = "https://kb7135ul7f.execute-api.us-east-2.amazonaws.com/default/isTheWorldFlat";
    private static final String ServiceID = "CDB7950D-73F1-4D4D-8E47-C090502DBD63";
    private static final String CharacteristicID = "DE9AEDA6-BA74-4093-BF08-EE7571051BF4";
    private static final int BROADCAST_DURATION = 60000;
    private static final long FRESHNESS_DURATION = 600000;
    private static final ParcelUuid uuid = new ParcelUuid(UUID.fromString(ServiceID));
    private static final boolean AUTO_CONNECT = false;

    private static boolean isNetworkBeingUsed = false;
    private static boolean isScanning = false;
    private static boolean isAdvertising = false;
    private static boolean isConnected = false;
    private static boolean scannerFoundResult = false;
    private static ArrayList<Byte> scannerMessageReceived = new ArrayList<>();
    private static ArrayList<Byte> advertiserMessageSent = new ArrayList<>();
    private TextView text;
    private Button button;
    private Handler mHandler;
    private long lastDownloadTime;
    private long scanStartTime;
    private ScanCallback scanCallback = null;
    private BluetoothGattServer advertiserServer = null;



    /*
    * Initializes member variables (view elements). Adds on click listeners to each button.
    * */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        text = findViewById(R.id.resultBox);
        button = findViewById(R.id.earthFlat);
        Button scanButton = findViewById(R.id.scanning);
        Button advertiseButton = findViewById(R.id.advertising);
        mHandler = new Handler();

        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isNetworkBeingUsed) return;
                isNetworkBeingUsed = true;
                downloadFromNetwork();
            }
        });

        advertiseButton.setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View v){
                if (isAdvertising) return;
                isAdvertising = true;
                broadcastResult();
            }
        });

        scanButton.setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View v){
                if (isScanning) return;
                isScanning = true;
                scanForResult();
            }
        });

    }

    private void downloadFromNetwork(){
        Log.i("NETWORK","downloadFromNetwork() : Downloading result from network");
        FetchTask network = new FetchTask();
        try {
            URL url = new URL(resourceURL);
            network.execute(url);
        }catch (Exception e) {
            Log.e("URL_FAILURE",e.toString());
        }
    }

    private void broadcastResult(){
        Log.i("ADVERTISER","broadcastResult() : Advertising results");
        BroadcastTask advertiser = new BroadcastTask();
        advertiser.execute();
    }

    private void scanForResult(){
        Log.i("SCANNER","scanForResult() : Scanning for results");
        ScannerTask scanner = new ScannerTask();
        scanner.execute();
    }
/*
*
*
* Network fetch task
*
*
* */
    private class FetchTask extends AsyncTask<URL,Void,String>{
        /*
        * Sends as many GET requests as there are URLs. Returns the results in String format.
        * */
        @Override
        protected String doInBackground(URL...sites){
            HttpURLConnection currConnection = null;
            InputStream currInput = null;
            StringBuilder responses = new StringBuilder();
            try{
                for (URL site : sites){
                    currConnection = (HttpURLConnection) site.openConnection();
                    currConnection.setRequestProperty("Connection", "close");
                    currInput = new BufferedInputStream(currConnection.getInputStream());
                    int currChar = currInput.read();
                    while(currChar!=-1){
                        responses.append((char) currChar);
                        currChar = currInput.read();
                    }
                    currConnection.disconnect();
                    currInput.close();
                }
            }catch(Exception e){
                Log.e("NETWORK_FAILURE",e.toString());
            }finally{
                if(currConnection!=null) currConnection.disconnect();
            }
            return responses.toString();
        }
        /*
        *Takes the result string from the GET requests, and fills in the text view with the result.
        * */
        @Override
        protected void onPostExecute(String result){
            TextView text = (TextView) findViewById(R.id.resultBox);
            text.setText(result);
            lastDownloadTime = System.currentTimeMillis();
            isNetworkBeingUsed = false;

            broadcastResult();
        }
    }
/*
*
*
* Advertisement class
*
*
*
* */
    private class BroadcastTask extends AsyncTask<Void,Void,Void>{
        private BluetoothAdapter mBluetoothAdapter;
        private BluetoothManager mBluetoothManager;
        private BluetoothLeAdvertiser mLeAdvertiser;
        private Handler broadcastHandler;

        @Override
        protected void onPreExecute() throws RuntimeException{
            mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            mBluetoothAdapter = mBluetoothManager.getAdapter();
            mLeAdvertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
            broadcastHandler = new Handler();
            for (byte b : text.getText().toString().getBytes()){
                advertiserMessageSent.add(b);
            }
            if (!mBluetoothAdapter.isEnabled()) mBluetoothAdapter.enable();
        }

        /*
        *
        * TODO: Sending data as a characteristic
        * */
        @Override
        protected Void doInBackground(Void...nulls) {

            final AdvertiseData data = new AdvertiseData.Builder()
                    .addServiceUuid(uuid)
                    .build();

            final AdvertiseData scanResponse = new AdvertiseData.Builder()
                    .addServiceData(uuid,MainActivity.longToByteArray(lastDownloadTime))
                    .build();

            final AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                    .setConnectable(true)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW)
                    .build();

            final AdvertiseCallback callback = new AdvertiseCallback() {
                @Override
                public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                    Log.i("ADVERTISING", "Started advertising successfully");
                    Log.i("ADVERTISING", settingsInEffect.toString());
                }

                @Override
                public void onStartFailure(int errorcode){
                    Log.i("ADVERTISING","Advertising failed " + errorcode);
                }
            };

            mLeAdvertiser.startAdvertising(settings,data,scanResponse,callback);

            broadcastHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!isAdvertising)
                        return;

                    mLeAdvertiser.stopAdvertising(callback);
                    Log.i("ADVERTISING","Successfully stopped advertising");
                    isAdvertising = false;

                    scanForResult();
                }
            }, lastDownloadTime + FRESHNESS_DURATION - System.currentTimeMillis());

            /*
            * At this point, we have posted a handler to stop advertising when data freshness
            * expires. We have also started advertising that we have a service, with a scan response
            * advertising when we downloaded the data for the service.
            *
                */
            final BluetoothGattService gattService = new BluetoothGattService(UUID.fromString(ServiceID),BluetoothGattService.SERVICE_TYPE_PRIMARY);
            final BluetoothGattCharacteristic gattCharacteristic = new BluetoothGattCharacteristic(UUID.fromString(CharacteristicID),
                    BluetoothGattCharacteristic.PROPERTY_READ,BluetoothGattCharacteristic.PERMISSION_READ);
            gattService.addCharacteristic(gattCharacteristic);

            BluetoothGattServerCallback gattCallback = new BluetoothGattServerCallback() {
                @Override
                public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
                    if (newState == BluetoothProfile.STATE_CONNECTED){
                        mLeAdvertiser.stopAdvertising(callback);
                        isAdvertising = false;
                        isConnected = true;
                        Log.i("CONNECTION","Connected to Bluetooth device");
                        Log.i("ADVERTISING","Stopped advertising");
                    }
                    else if (newState == BluetoothProfile.STATE_DISCONNECTED){
                        if (lastDownloadTime + FRESHNESS_DURATION - System.currentTimeMillis() > 100){
                            if (!isAdvertising){
                                mLeAdvertiser.startAdvertising(settings,data,scanResponse,callback);
                                isAdvertising = true;
                                isConnected = false;
                                Log.i("CONNECTION","Disconnected from Bluetooth Device");
                                Log.i("ADVERTISING","Started advertising again");
                            }
                        }
                    }else{
                        Log.i("CONNECTION", "Unknown state. New State: "+newState);
                    }
                }

                @Override
                public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattCharacteristic characteristic) {
                    Log.i("CONNECTION","Read request received");
                    if (characteristic.equals(gattCharacteristic)){

                        byte[] message = new byte[20];
                        boolean endOfMessage = false;

                        for (int i = 0; i<20;i++){
                            if (advertiserMessageSent.isEmpty()){
                                message[i] = (byte) 4; //EOF
                                endOfMessage = true;
                                break;
                            }
                            message[i] = advertiserMessageSent.get(0);
                            advertiserMessageSent.remove(0);
                        }
                        advertiserServer.sendResponse(device,requestId,BluetoothGatt.GATT_SUCCESS,0,message);
                        if (endOfMessage) {
                            Log.i("CONNECTION", "End of message sent. Resetting message.");
                            byte[] nextMessage = text.getText().toString().getBytes();
                            for (byte b : nextMessage) {
                                advertiserMessageSent.add(b);
                            }
                        }
                    }
                }

            };
            BluetoothGattServer gattServer = mBluetoothManager.openGattServer(MainActivity.this.getApplicationContext(),gattCallback);
            advertiserServer = gattServer;
            gattServer.clearServices();
            gattServer.addService(gattService);
            return null;
        }
    }
/*
*
*
* Scanner task
*
*
* */
    private class ScannerTask extends AsyncTask<Void,Void,Void>{
        private BluetoothAdapter mBluetoothAdapter;
        private BluetoothLeScanner mLeScanner;
        private Handler scanHandler;

        @Override
        protected void onPreExecute() throws RuntimeException{
            /*Initialize all of the relevant hardware interface classes*/
            final BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            mBluetoothAdapter = bluetoothManager.getAdapter();
            mLeScanner = mBluetoothAdapter.getBluetoothLeScanner();
            scanHandler = new Handler();

            if (!mBluetoothAdapter.isEnabled()) mBluetoothAdapter.enable();
        }
        /*
        * Scans for BROADCAST_DURATION seconds. If an advertising device is found, connects and
        * downloads data. Stops scanning. Launches advertiser for remaining expiration time, calculated
        * by MicroCDN class.
        *
        * */
        @Override
        protected Void doInBackground(Void...voids){
            /*Settings for the scanner*/
            final ScanSettings settings = new ScanSettings.Builder()
                    .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .build();
            /*Only find the service that we want*/
            final ScanFilter filter = new ScanFilter.Builder()
                    .setServiceUuid(uuid)
                    .build();
            final List<ScanFilter> filters = new ArrayList<>();
            filters.add(filter);

            //Adding stuff from here
            final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                    if (status!=BluetoothGatt.GATT_SUCCESS) {
                        Log.i("CONNECTION","Connection failed, status: "+status);
                        if (!isScanning){
                            if (scanStartTime + BROADCAST_DURATION - System.currentTimeMillis()>100){
                                isScanning = true;
                                mLeScanner.startScan(filters,settings,scanCallback);
                            }
                        }
                    }
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        if (isScanning){
                            Log.i("CONNECTION","Connected to device");
                            mLeScanner.stopScan(scanCallback);
                            isConnected = true;
                            isScanning = false;
                            gatt.discoverServices();
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        Log.i("CONNECTION","Disconnected");
                        if (!scannerFoundResult && !isScanning){
                            if (scanStartTime + BROADCAST_DURATION - System.currentTimeMillis()>100){
                                isScanning = true;
                                isConnected = false;
                                scannerMessageReceived = new ArrayList<Byte>(); //reset message
                                mLeScanner.startScan(filters,settings,scanCallback);
                            }
                        }
                    }
                }

                @Override
                public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                    if (status!=BluetoothGatt.GATT_SUCCESS){
                        Log.i("CONNECTION","Read request failed, status: "+status);
                        return;
                    }
                    for (Byte b : characteristic.getValue()){
                        if (b==(byte) 4){
                            scannerFoundResult = true;
                            final StringBuilder result = new StringBuilder();
                            for (byte messageByte : scannerMessageReceived){
                                result.append((char)messageByte);
                            }
                            mHandler.postAtFrontOfQueue(new Runnable() {
                                @Override
                                public void run() {
                                    Log.i("CONNECTION","Made message: "+result.toString());
                                    text.setText(result.toString());

                                    long expirationTime = lastDownloadTime + FRESHNESS_DURATION - System.currentTimeMillis();
                                    if (expirationTime>10000){
                                        Log.i("SCANNER","Advertising downloaded message for : "+expirationTime);
                                        broadcastResult();
                                    }else{
                                        Log.i("SCANNER","Not enough time remaining to advertise");
                                    }
                                }
                            });
                            Log.i("CONNECTION","Received entire message, disconnecting.");
                            gatt.disconnect();
                            gatt.close();
                            return;
                        }
                        scannerMessageReceived.add(b);
                    }
                    Log.i("CONNECTION","Received packet");
                    gatt.readCharacteristic(characteristic);
                }

                @Override
                public void onServicesDiscovered(BluetoothGatt gatt, int status){
                    if (status!=BluetoothGatt.GATT_SUCCESS){
                        Log.i("CONNECTION","Service discovery failed, status: " + status);
                        return;
                    }
                    BluetoothGattCharacteristic gattCharacteristic = gatt.getService(UUID.fromString(ServiceID))
                            .getCharacteristic(UUID.fromString(CharacteristicID));
                    Log.i("CONNECTION","Discovered service ID: "+gatt.getService(UUID.fromString(ServiceID)).getUuid().toString());
                    Log.i("CONNECTION","Discovered characteristic: "+gattCharacteristic.getUuid().toString());
                    Log.i("CONNECTION","Attempting to read data");
                    gatt.readCharacteristic(gattCharacteristic);
                }
            };

            /*On scan result, we update the text box and then stop scanning*/
            final ScanCallback callback = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    Log.i("SCANNING","Callback: "+callbackType + ", "+result.getScanRecord().toString());
                    byte[] dataAsBytes = result.getScanRecord().getServiceData().get(uuid);
                    lastDownloadTime = byteArrayToLong(dataAsBytes);
                    Log.i("DOWNLOAD TIME","Scan download time: "+lastDownloadTime);

                    BluetoothDevice advertiser = result.getDevice();
                    advertiser.connectGatt(MainActivity.this.getApplicationContext(),false,gattCallback);
                }

                @Override
                public void onScanFailed(int errorCode){
                    Log.i("SCANNING","Error code: "+ errorCode);
                    isScanning = false;
                }

            };
            scanCallback = callback;
            /*Start scanning*/
            mLeScanner.startScan(filters, settings, callback);
            scanStartTime = System.currentTimeMillis();
            /*If we have not found a result in BROADCAST_DURATION milliseconds, stop scanning*/
            scanHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (isScanning){
                        isScanning = false;
                        mLeScanner.stopScan(callback);
                        Log.i("SCANNING","Stopped scanning successfully");
                        downloadFromNetwork();
                    }

                }
            },BROADCAST_DURATION);

            return null;
        }
    }


    private static byte[] longToByteArray(long l){
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
        buffer.putLong(l);
        return buffer.array();
    }

    private static long byteArrayToLong(byte[] array){
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
        buffer.put(array);
        buffer.flip();//need flip
        return buffer.getLong();
    }
}
