package com.example.labb3b;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class DeviceActivity extends AppCompatActivity {

    // Movesense 2.0 UUIDs (should be placed in resources file)
    public static final UUID MOVESENSE_2_0_SERVICE =
            UUID.fromString("34802252-7185-4d5d-b431-630e7050e8f0");
    public static final UUID MOVESENSE_2_0_COMMAND_CHARACTERISTIC =
            UUID.fromString("34800001-7185-4d5d-b431-630e7050e8f0");
    public static final UUID MOVESENSE_2_0_DATA_CHARACTERISTIC =
            UUID.fromString("34800002-7185-4d5d-b431-630e7050e8f0");
    // UUID for the client characteristic, which is necessary for notifications
    public static final UUID CLIENT_CHARACTERISTIC_CONFIG =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final String IMU_COMMAND = "Meas/IMU6/13"; // see documentation
    private final byte MOVESENSE_REQUEST = 1, MOVESENSE_RESPONSE = 2, REQUEST_ID = 99;

    private BluetoothDevice mSelectedDevice = null;
    private BluetoothGatt mBluetoothGatt = null;

    private Handler mHandler;

    private TextView mDeviceView;
    private TextView mDataView;
    private Button mToggleButton;

    private double cPitch;
    private List<Integer> timestamps;
    private List<Double> degrees;
    private boolean toggle;
    private double startTime;

    private static final String LOG_TAG = "DeviceActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device);
        mDeviceView = findViewById(R.id.device_view);
        mDataView = findViewById(R.id.data_view);
        mToggleButton = findViewById(R.id.toggleButton);
        cPitch = 0;
        toggle = false;
        timestamps = new ArrayList<>();
        degrees = new ArrayList<>();
        Intent intent = getIntent();
        // Get the selected device from the intent
        mSelectedDevice = intent.getParcelableExtra(ScanActivity.SELECTED_DEVICE);
        if (mSelectedDevice == null) {
            Toast t = Toast.makeText(DeviceActivity.this, "Device not found", Toast.LENGTH_LONG);
            mDeviceView.setText(R.string.no_device);
        } else {
            mDeviceView.setText(mSelectedDevice.getName());
        }

        mToggleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v)
            {
                if (!toggle) {
                    toggle = true;
                    startTime = System.currentTimeMillis();
                } else {
                    toggle = false;
                }
            }
        });

        mHandler = new Handler();
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
    @Override
    protected void onStart() {
        super.onStart();
        if (mSelectedDevice != null) {
            // Connect and register call backs for bluetooth gatt
            mBluetoothGatt =
                    mSelectedDevice.connectGatt(this, false, mBtGattCallback);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
    @Override
    protected void onStop() {
        super.onStop();
        if (mBluetoothGatt != null) {
            mBluetoothGatt.disconnect();
            try {
                mBluetoothGatt.close();
            } catch (Exception e) {
                // ugly, but this is to handle a bug in some versions in the Android BLE API
            }
        }
    }

    /**
     * Callbacks for bluetooth gatt changes/updates
     * The documentation is not always clear, but most callback methods seems to
     * be executed on a worker thread - hence use a Handler when updating the ui.
     */

    private final BluetoothGattCallback mBtGattCallback = new BluetoothGattCallback() {

        @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                mBluetoothGatt = gatt;
                mHandler.post(new Runnable() {
                    public void run() {
                        mDataView.setText(R.string.connected);
                    }
                });
                // Discover services
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // Close connection and display info in ui
                mBluetoothGatt = null;
                mHandler.post(new Runnable() {
                    public void run() {
                        mDataView.setText(R.string.disconnected);
                    }
                });
            }
        }

        @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
        @Override
        public void onServicesDiscovered(final BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Debug: list discovered services
                List<BluetoothGattService> services = gatt.getServices();
                for (BluetoothGattService service : services) {
                    Log.i(LOG_TAG, service.getUuid().toString());
                }

                // Get the Movesense 2.0 IMU service
                BluetoothGattService movesenseService = gatt.getService(MOVESENSE_2_0_SERVICE);
                if (movesenseService != null) {
                    // debug: service present, list characteristics
                    List<BluetoothGattCharacteristic> characteristics =
                            movesenseService.getCharacteristics();
                    for (BluetoothGattCharacteristic chara : characteristics) {
                        Log.i(LOG_TAG, chara.getUuid().toString());
                    }

                    // Write a command, as a byte array, to the command characteristic
                    // Callback: onCharacteristicWrite
                    BluetoothGattCharacteristic commandChar =
                            movesenseService.getCharacteristic(
                                    MOVESENSE_2_0_COMMAND_CHARACTERISTIC);
                    // command example: 1, 99, "/Meas/Acc/13"
                    byte[] command =
                            TypeConverter.stringToAsciiArray(REQUEST_ID, IMU_COMMAND);
                    commandChar.setValue(command);
                    boolean wasSuccess = mBluetoothGatt.writeCharacteristic(commandChar);
                    Log.i("writeCharacteristic", "was success=" + wasSuccess);
                } else {
                    mHandler.post(new Runnable() {
                        public void run() {
                            Toast t = Toast.makeText(DeviceActivity.this, "Service not found", Toast.LENGTH_LONG);
                            t.show();
                        }
                    });
                }
            }
        }

        @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic
                characteristic, int status) {
            Log.i(LOG_TAG, "onCharacteristicWrite " + characteristic.getUuid().toString());

            // Enable notifications on data from the sensor. First: Enable receiving
            // notifications on the client side, i.e. on this Android device.
            BluetoothGattService movesenseService = gatt.getService(MOVESENSE_2_0_SERVICE);
            BluetoothGattCharacteristic dataCharacteristic =
                    movesenseService.getCharacteristic(MOVESENSE_2_0_DATA_CHARACTERISTIC);
            // second arg: true, notification; false, indication
            boolean success = gatt.setCharacteristicNotification(dataCharacteristic, true);
            if (success) {
                Log.i(LOG_TAG, "setCharactNotification success");
                // Second: set enable notification server side (sensor). Why isn't
                // this done by setCharacteristicNotification - a flaw in the API?
                BluetoothGattDescriptor descriptor =
                        dataCharacteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                gatt.writeDescriptor(descriptor); // callback: onDescriptorWrite
            } else {
                Log.i(LOG_TAG, "setCharacteristicNotification failed");
            }
        }

        @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
        @Override
        public void onDescriptorWrite(final BluetoothGatt gatt, BluetoothGattDescriptor
                descriptor, int status) {
            Log.i(LOG_TAG, "onDescriptorWrite, status " + status);

            if (CLIENT_CHARACTERISTIC_CONFIG.equals(descriptor.getUuid()))
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    // if success, we should receive data in onCharacteristicChanged
                    mHandler.post(new Runnable() {
                        public void run() {
                            mDeviceView.setText(R.string.notifications_enabled);
                        }
                    });
                }
        }

        /**
         * Callback called on characteristic changes, e.g. when a sensor data value is changed.
         * This is where we receive notifications on new sensor data.
         */
        @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic
                characteristic) {
            // debug
            // Log.i(LOG_TAG, "onCharacteristicChanged " + characteristic.getUuid());

            // if response and id matches
            if (MOVESENSE_2_0_DATA_CHARACTERISTIC.equals(characteristic.getUuid())) {
                byte[] data = characteristic.getValue();
                if (data[0] == MOVESENSE_RESPONSE && data[1] == REQUEST_ID) {
                    // NB! use length of the array to determine the number of values in this
                    // "packet", the number of values in the packet depends on the frequency set(!)
                    int len = data.length;
                    double alpha = 0.1;
                    double dT = 1/52;
                    // ...
                    if(len!=0)//check here
                    {
                        return;
                    }
                    // parse and interpret the data, ...
                    int time = TypeConverter.fourBytesToInt(data, 2);
                    float accX = TypeConverter.fourBytesToFloat(data, 6);
                    float accY = TypeConverter.fourBytesToFloat(data, 10);
                    float accZ = TypeConverter.fourBytesToFloat(data, 14);
                    float gyroX = TypeConverter.fourBytesToFloat(data, 18);
                    float gyroY = TypeConverter.fourBytesToFloat(data, 22);
                    float gyroZ = TypeConverter.fourBytesToFloat(data, 26);

                    double accAngle = Math.sqrt(accX*accX+accY*accY);
                    accAngle = (accZ/accAngle);
                    accAngle = Math.atan(accAngle);
                    accAngle = Math.toDegrees(accAngle);

                    cPitch = alpha * (cPitch + dT* gyroZ) + (1-alpha)*accAngle;

                    String accStr = "" + accX + " " + accY + " " + accZ;
                    Log.i("acc data", "" + time + " " + accStr);

                    if(toggle)
                    {
                        double timeA = System.currentTimeMillis() - startTime;
                        if (timeA <= 10000) {
                            degrees.add(cPitch);
                            timestamps.add(time);
                            Log.d("tag", "stamped");
                        }
                        else
                        {
                            toggle = false;
                            WriteToFile();
                        }
                    }
                    final String viewDataStr = String.format("%.2f", cPitch);
                    mHandler.post(new Runnable() {
                        public void run() {
                            mDeviceView.setText("" + time + " ms");
                            mDataView.setText(viewDataStr);
                        }
                    });
                }
            }
        }

        @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic
                characteristic, int status) {
            Log.i(LOG_TAG, "onCharacteristicRead " + characteristic.getUuid().toString());
        }
    };

    public void WriteToFile() {
        PrintWriter writer = null;
        try {
            OutputStream os = this.openFileOutput(
                    "data.txt", Context.MODE_PRIVATE);
            writer = new PrintWriter(os);
            for(int i = 0; i < timestamps.size(); i++)
            {
                writer.println(timestamps.get(i));
            }
            for(int i = 0; i < degrees.size(); i++)
            {
                writer.println(degrees.get(i));
            }
            Toast.makeText(getBaseContext(), "File saved successfully!", Toast.LENGTH_SHORT).show();
        }
        catch(IOException ioe) {
            Toast.makeText(getBaseContext(), "Error", Toast.LENGTH_SHORT).show();
        }
        finally {
            if(writer != null) writer.close();
        }
    }
}