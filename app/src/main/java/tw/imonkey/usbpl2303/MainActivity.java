package tw.imonkey.usbpl2303;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import org.greenrobot.eventbus.EventBus;

import java.io.UnsupportedEncodingException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Calendar;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;



public class MainActivity extends Activity {
    //**************USBSerialPort
    private static final String TAG = MainActivity.class.getSimpleName();
    //   private static final int USB_VENDOR_ID = 0x0403;//arduino nano FT232RL
    //   private static final int USB_PRODUCT_ID = 0x6001;
    // private static final int USB_VENDOR_ID = 0x2341;//arduino uno(BT)
    //   private static final int USB_PRODUCT_ID = 0x0043;
    private static final int USB_VENDOR_ID = 0x067b;//PL2303HXD
    private static final int USB_PRODUCT_ID = 0x2303;

    private UsbManager usbManager;
    private UsbDeviceConnection connection;
    private UsbSerialDevice serialDevice;

    //set usb serialport parameters
    int baudRate=9600 ;
    int dataBits = UsbSerialInterface.DATA_BITS_8;
    int stopBits = UsbSerialInterface.STOP_BITS_1;
    int parity = UsbSerialInterface.PARITY_NONE;
    int flowControl= UsbSerialInterface.FLOW_CONTROL_OFF;
    String buffer = "";
    //set serialport protocol parameters
    byte STX=0x3A,ETX=0x0A; //0x02:STX,0x03:ETX,0x05:ENQ,0x0A:'/n',0x0A:LF,0x3A:':',0xOD:CR

    //*************firebase*****************
    String memberEmail,deviceId;
    public static final String devicePrefs = "devicePrefs";
    DatabaseReference  mTX, mRX, mFriend, mRS232Live,presenceRef,lastOnlineRef,connectedRef,connectedRefF;
    public MySocketServer mServer;
    private static final int SERVER_PORT = 9402;
    Map<String, Object> alert = new HashMap<>();
    //*******PLC****************
    private Handler handler;
    Runnable runnable;
    int timer=1000 ;
    String cmd ;

    private UsbSerialInterface.UsbReadCallback callback = new UsbSerialInterface.UsbReadCallback() {
        @Override
        public void onReceivedData(byte[] data) {
            try {
                String dataUtf8 = new String(data, "UTF-8");
                buffer += dataUtf8;
                int index;
                while ((index = buffer.indexOf(ETX)) != -1) {  //string.indexOf('\n')=-1 =>'\n' not exists
                    final String dataStr = buffer.substring(0, index + 1).trim();
                    buffer = buffer.length() == index ? "" : buffer.substring(index + 1);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            onSerialDataReceived(dataStr);
                        }
                    });
                }
            } catch (UnsupportedEncodingException e) {
                Log.e(TAG, "Error receiving USB data", e);
            }
        }
    };

    private final BroadcastReceiver usbDetachedReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null && device.getVendorId() == USB_VENDOR_ID && device.getProductId() == USB_PRODUCT_ID) {
                    Log.i(TAG, "USB device detached");
                    stopUsbConnection();
                }
            }
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Taipei"));
        EventBus.getDefault().register(this);
        SharedPreferences settings = getSharedPreferences(devicePrefs, Context.MODE_PRIVATE);
        memberEmail = settings.getString("memberEmail",null);
        deviceId = settings.getString("deviceId",null);

        if (memberEmail==null) {
            startServer();
        }else{
            deviceOnline();
            usbManager = getSystemService(UsbManager.class);
            // Detach events are sent as a system-wide broadcast
            IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
            registerReceiver(usbDetachedReceiver, filter);
            transferUartTX();
            periodRequestDevice();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startUsbConnection();
        EventBus.getDefault().register(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(usbDetachedReceiver);
        stopUsbConnection();
        EventBus.getDefault().unregister(this);
    }

    private void startUsbConnection() {
        Map<String, UsbDevice> connectedDevices = usbManager.getDeviceList();

        if (!connectedDevices.isEmpty()) {
            for (UsbDevice device : connectedDevices.values()) {
                if (device.getVendorId() == USB_VENDOR_ID && device.getProductId() == USB_PRODUCT_ID) {
                    Log.i(TAG, "Device found: " + device.getDeviceName());
                    startSerialConnection(device);
                    return;
                }
            }
        }
        Log.w(TAG, "Could not start USB connection - No devices found");
    }

    private void startSerialConnection(UsbDevice device) {
        Log.i(TAG, "Ready to open USB device connection");
        connection = usbManager.openDevice(device);
        int iface = 0;
        serialDevice = UsbSerialDevice.createUsbSerialDevice(device, connection,iface);
        if (serialDevice != null) {
            if (serialDevice.open()) {
                serialDevice.setBaudRate(baudRate);
                serialDevice.setDataBits(dataBits);
                serialDevice.setStopBits(stopBits);
                serialDevice.setParity(parity);
                serialDevice.setFlowControl(flowControl);
                serialDevice.read(callback);
                Log.i(TAG, "Serial connection opened");
            } else {
                Log.w(TAG, "Cannot open serial connection");
            }
        } else {
            Log.w(TAG, "Could not create Usb Serial Device");
        }
    }

    private void onSerialDataReceived(String data) {
        //todo: data parser
        //data log
        mRX= FirebaseDatabase.getInstance().getReference("/RS232/"+deviceId+"/RX/");
        Map<String, Object> RX = new HashMap<>();
        RX.clear();
        RX.put("message",data);
        RX.put("timeStamp", ServerValue.TIMESTAMP);
        mRX.push().setValue(RX);
        Log.i(TAG, "Serial data received: " + data);
        //
        alert(data);
    }

    private void stopUsbConnection() {
        try {
            if (serialDevice != null) {
                serialDevice.close();
            }

            if (connection != null) {
                connection.close();
            }
        } finally {
            serialDevice = null;
            connection = null;
        }
    }

    // websocket server
    private void startServer() {
        InetAddress inetAddress = getInetAddress();
        if (inetAddress == null) {
            return;
        }
        mServer = new MySocketServer(new InetSocketAddress(inetAddress.getHostAddress(), SERVER_PORT));
        mServer.start();
    }

    private static InetAddress getInetAddress() {
        try {
            for (Enumeration en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface networkInterface = (NetworkInterface) en.nextElement();

                for (Enumeration enumIpAddr = networkInterface.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = (InetAddress) enumIpAddr.nextElement();

                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        return inetAddress;
                    }
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }

        return null;
    }

    @SuppressWarnings("UnusedDeclaration")
    public void onEvent(SocketMessageEvent event) {
        String message = event.getMessage();
        String[] mArray = message.split(",");
        if (mArray.length==2) {
            memberEmail = mArray[0];
            deviceId =  mArray[1];
            SharedPreferences.Editor editor = getSharedPreferences(devicePrefs, Context.MODE_PRIVATE).edit();
            editor.putString("memberEmail",memberEmail);
            editor.putString("deviceId",deviceId);
            editor.apply();
            mServer.sendMessage("echo: " + message);
            mServer.sendMessage("OK?->Reboot,please");
        }
    }

    //device online check
    private void deviceOnline(){
        mRS232Live=FirebaseDatabase.getInstance().getReference("/RS232/"+deviceId+"/connection");//for log activity
        mRS232Live.setValue(true);
        mRS232Live.onDisconnect().setValue(null);

        presenceRef = FirebaseDatabase.getInstance().getReference("/master/"+memberEmail.replace(".", "_")+"/"+deviceId+"/connection");//for boss's main activity
        presenceRef.setValue(true);
        presenceRef.onDisconnect().setValue(null);
        lastOnlineRef =FirebaseDatabase.getInstance().getReference("/master/"+memberEmail.replace(".", "_")+"/"+deviceId+"/lastOnline");
        lastOnlineRef.onDisconnect().setValue(ServerValue.TIMESTAMP);
        connectedRef = FirebaseDatabase.getInstance().getReference(".info/connected");
        connectedRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                boolean connected = snapshot.getValue(Boolean.class);
                if (connected) {
                    presenceRef.setValue(true);
                    mRS232Live.setValue(true);
                }
            }
            @Override
            public void onCancelled(DatabaseError error) {
            }
        });
        mFriend= FirebaseDatabase.getInstance().getReference("/DEVICE/"+deviceId+"/friend"); //for friend's main activity
        mFriend.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                for (DataSnapshot childSnapshot : snapshot.getChildren()) {
                    final DatabaseReference presenceRefF= FirebaseDatabase.getInstance().getReference("/friend/"+childSnapshot.getValue().toString().replace(".", "_")+"/"+deviceId+"/connection");
                    presenceRefF.setValue(true);
                    presenceRefF.onDisconnect().setValue(null);
                    connectedRefF = FirebaseDatabase.getInstance().getReference(".info/connected");
                    connectedRefF.addValueEventListener(new ValueEventListener() {
                        @Override
                        public void onDataChange(DataSnapshot snapshot) {
                            boolean connected = snapshot.getValue(Boolean.class);
                            if (connected) {
                                presenceRefF.setValue(true);
                            }
                        }
                        @Override
                        public void onCancelled(DatabaseError error) {
                        }
                    });
                }
            }
            @Override
            public void onCancelled(DatabaseError databaseError) {}
        });
    }
    private void transferUartTX() {
        mTX= FirebaseDatabase.getInstance().getReference("/RS232/"+deviceId+"/TX/");
        mTX.addChildEventListener(new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot dataSnapshot, String s) {

                if (dataSnapshot.child("message").getValue()!= null) {
                    String oneTimeCMD=dataSnapshot.child("message").getValue().toString();
                    serialDevice.write((STX+oneTimeCMD+ETX).getBytes());
                    Log.i(TAG, "Serial data send: " + cmd);
                    //   requestPLC();
                }
            }

            @Override
            public void onChildRemoved(DataSnapshot dataSnapshot) {}

            @Override
            public void onChildMoved(DataSnapshot dataSnapshot, String s) {}

            @Override
            public void onChildChanged(DataSnapshot dataSnapshot, String s) {}

            @Override
            public void onCancelled(DatabaseError databaseError) {}
        });
    }

    private void periodRequestDevice(){
        handler = new Handler();
        runnable = new Runnable()
        {
            @Override
            public void run()
            {
                serialDevice.write((STX+cmd+ETX).getBytes()); // Async-like operation now! :)
                handler.postDelayed(this, timer);
            }
        };
        handler.postDelayed(runnable, timer);
    }


    private void alert(String message){
        NotifyUser.topicsPUSH(deviceId,memberEmail,"PLC通知",message);
        DatabaseReference mAlertMaster= FirebaseDatabase.getInstance().getReference("/master/"+memberEmail.replace(".", "_")+"/"+deviceId+"/alert");
        alert.clear();
        alert.put("message",message);
        alert.put("timeStamp", ServerValue.TIMESTAMP);
        mAlertMaster.setValue(alert);
        DatabaseReference mFriend= FirebaseDatabase.getInstance().getReference("/devices/"+memberEmail.replace(".", "_")+"/"+deviceId+"/friend");
        mFriend.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                for (DataSnapshot childSnapshot : snapshot.getChildren()) {
                    DatabaseReference mAlertFriend= FirebaseDatabase.getInstance().getReference("/friend/"+childSnapshot.getValue().toString().replace(".", "_")+"/"+deviceId+"/alert");
                    mAlertFriend.setValue(alert);
                }
            }
            @Override
            public void onCancelled(DatabaseError databaseError) {}
        });
    }


}


