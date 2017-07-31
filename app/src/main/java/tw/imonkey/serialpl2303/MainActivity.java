package tw.imonkey.serialpl2303;

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
import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.GpioCallback;
import com.google.android.things.pio.PeripheralManagerService;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

import de.greenrobot.event.EventBus;



public class MainActivity extends Activity {
    //**************USBSerialPort
    private static final String TAG = MainActivity.class.getSimpleName();
    private static final int USB_VENDOR_ID = 0x067B;//PL2303HXD
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
    String buffer = "" , CMD = "";
    //*******firebase*************
    String memberEmail,deviceId;
    public static final String devicePrefs = "devicePrefs";
    DatabaseReference mSETTINGS,mRequest,mAlert, mLog, mTX, mRX,mUsers,presenceRef,connectedRef;
    int logCount,RXCount,TXCount;
    int dataCount;
    int limit=1000;//max Logs (even number)
    public MySocketServer mServer;
    private static final int SERVER_PORT = 9402;
    Map<String, Object> alert = new HashMap<>();
    Map<String, Object> register = new HashMap<>();
    Map<String, String> RXCheck = new HashMap<>();
    ArrayList<String> users = new ArrayList<>();
    boolean restart=true;

    Gpio RESETGpio;
    String RESET="BCM26";

    //*******PLC****************
    //set serialport protocol parameters
    String STX=new String(new char[]{0x02});
    String ETX=new String(new char[]{0x03});
    String ENQ=new String(new char[]{0x05});
    String newLine=new String(new char[]{0x0D,0x0A});

    private Handler handler;
    Runnable runnable;
    int countPCMD=0;
    int timer=1000 ;
    boolean oneTimeCMDCheck=false;
    ArrayList<String> PCMD = new ArrayList<>();
    String oneTimeCMD;
    private UsbSerialInterface.UsbReadCallback callback = new UsbSerialInterface.UsbReadCallback() {
        @Override
        public void onReceivedData(byte[] data) {
            try {
                String dataUtf8 = new String(data, "UTF-8");
                buffer += dataUtf8;
                int index;
                while ((index = buffer.indexOf(newLine)) != -1) {  //string.indexOf('\n')=-1 =>'\n' not exists
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
        init();
        deviceOnline();
        listenUartTX();
        requestDevice();
        alert("RS232智慧機重新啟動!");

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
        if (handler!=null) {
            handler.removeCallbacks(runnable);
            handler=null;
        }

        if ( RESETGpio != null) {
            try {
                RESETGpio.close();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                RESETGpio = null;
            }
        }
    }

    private void init(){
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Taipei"));
        SharedPreferences settings = getSharedPreferences(devicePrefs, Context.MODE_PRIVATE);
        memberEmail = settings.getString("memberEmail",null);
        deviceId = settings.getString("deviceId",null);
        logCount = settings.getInt("logCount",0);
        TXCount = settings.getInt("TXCount",0);
        RXCount = settings.getInt("RXCount",0);
        PeripheralManagerService service = new PeripheralManagerService();
        if (memberEmail==null) {
            memberEmail="test@po-po.com";
            deviceId="PLC_RS232_test";
            DatabaseReference mAddTestDevice=FirebaseDatabase.getInstance().getReference("/DEVICE/");
            Map<String, Object> addTest = new HashMap<>();
            addTest.put("companyId","po-po") ;
            addTest.put("device","serialpl2303");
            addTest.put("deviceType","PLC監控機"); //PLC監控機
            addTest.put("description","Android things rs232 test");
            addTest.put("masterEmail",memberEmail) ;
            addTest.put("timeStamp", ServerValue.TIMESTAMP);
            addTest.put("topics_id",deviceId);
            Map<String, Object> user = new HashMap<>();
            user.put(memberEmail.replace(".","_"),memberEmail);
            addTest.put("users",user);
            mAddTestDevice.child(deviceId).setValue(addTest);
            startServer();
        }else{
            try {
                RESETGpio = service.openGpio(RESET);
                RESETGpio.setDirection(Gpio.DIRECTION_IN);
                RESETGpio.setEdgeTriggerType(Gpio.EDGE_BOTH);
                RESETGpio.registerGpioCallback(new GpioCallback() {
                    @Override
                    public boolean onGpioEdge(Gpio gpio) {

                        try {
                            if (RESETGpio.getValue()){
                                startServer();
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        return true;
                    }
                });
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        usbManager = getSystemService(UsbManager.class);
        // Detach events are sent as a system-wide broadcast
        IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbDetachedReceiver, filter);

        mRX = FirebaseDatabase.getInstance().getReference("/DEVICE/"+deviceId+"/RX/");
        mTX = FirebaseDatabase.getInstance().getReference("/DEVICE/"+deviceId+"/TX/");
        mRequest= FirebaseDatabase.getInstance().getReference("/DEVICE/"+deviceId+"/SETTINGS/CMD/");
        mLog=FirebaseDatabase.getInstance().getReference("/DEVICE/" + deviceId+"/LOG/");
        mSETTINGS = FirebaseDatabase.getInstance().getReference("/DEVICE/" + deviceId + "/SETTINGS");
        mAlert= FirebaseDatabase.getInstance().getReference("/DEVICE/"+ deviceId + "/alert");
        mUsers= FirebaseDatabase.getInstance().getReference("/DEVICE/"+deviceId+"/users/");
        mUsers.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                users.clear();
                for (DataSnapshot childSnapshot : snapshot.getChildren()) {
                    users.add(childSnapshot.getValue().toString());
                }
            }
            @Override
            public void onCancelled(DatabaseError databaseError) {}
        });
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
        int iface = 0;// multiple devices
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
        deviceRespond(data);
        Log.i(TAG, "Serial data received: " + data);
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

    private void listenUartTX() {
        mTX.limitToLast(1).addChildEventListener(new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot dataSnapshot, String s) {
                if (dataSnapshot.child("message").getValue()!= null && !restart) {
                    oneTimeCMD=dataSnapshot.child("message").getValue().toString().trim();
                    serialDevice.write((ENQ+oneTimeCMD+newLine).getBytes());
                    oneTimeCMDCheck=true;
                    Log.i(TAG, "Serial data send: " + oneTimeCMD);
                }else{
                    restart=false;//
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

    private void deviceRespond(String data){
        Map<String, Object> RX = new HashMap<>();
        if(oneTimeCMDCheck){
            alert(data);
            oneTimeCMDCheck=false;
            RX.clear();
            RX.put("message", oneTimeCMD + ":" + data);
            RX.put("timeStamp", ServerValue.TIMESTAMP);
            mRX.push().setValue(RX);
            mLog.push().setValue(RX);
            RXCount++;
            logCount++;
            SharedPreferences.Editor editor = getSharedPreferences(devicePrefs, Context.MODE_PRIVATE).edit();
            editor.putInt("RXCount",RXCount);
            editor.putInt("logCount",logCount);
            editor.apply();
        }else if (RXCheck.get(CMD)!=null) {
            if (!data.equals(RXCheck.get(CMD))) {
                alert(CMD + ":" + data);
                RX.clear();
                RX.put("message", CMD + ":" + data);
                RX.put("timeStamp", ServerValue.TIMESTAMP);
                mRX.push().setValue(RX);
                mLog.push().setValue(RX);
                RXCheck.put(CMD, data);
                RXCount++;
                logCount++;
                SharedPreferences.Editor editor = getSharedPreferences(devicePrefs, Context.MODE_PRIVATE).edit();
                editor.putInt("RXCount", RXCount);
                editor.putInt("logCount", logCount);
                editor.apply();
            } else{
                Log.i(TAG, "Serial data is no change." );
            }
        }
        if (RXCount>(limit+(limit)/2)) {
            dataLimit(mRX,limit);
            RXCount= RXCount-(limit)/2;
        }
        if (RXCount>(limit+(limit)/2)) {
            dataLimit(mLog,limit);
            logCount= logCount-(limit)/2;
        }

    }

    private void requestDevice(){
        mRequest.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (handler!=null) {
                    handler.removeCallbacks(runnable);
                    handler=null;
                }
                PCMD.clear();
                RXCheck.clear();
                for (DataSnapshot childSnapshot : snapshot.getChildren()) {
                    if (childSnapshot.getValue() != null) {
                        String cmd = childSnapshot.getValue().toString().trim();
                        PCMD.add(cmd);
                        RXCheck.put(cmd,"");
                        serialDevice.write((ENQ + cmd + newLine).getBytes());
                        Log.i(TAG, "Serial data send: " + CMD);
                    }
                }
                reqTimer();
            }
            @Override
            public void onCancelled(DatabaseError databaseError) {}
        });
    }

    private void reqTimer() {
        countPCMD = 0;
        handler = new Handler();
        runnable = new Runnable() {
            @Override
            public void run() {
                if(PCMD.size()>0) {
                    CMD= PCMD.get(countPCMD);
                    serialDevice.write((ENQ + CMD + newLine).getBytes()); // Async-like operation now! :)
                    Log.i(TAG, "Serial data send:"+PCMD.get(countPCMD));
                    if (countPCMD < (PCMD.size() - 1)) {
                        countPCMD++;
                    } else {
                        countPCMD = 0;
                    }
                }
                handler.postDelayed(this, timer);
            }
        };
        handler.postDelayed(runnable,timer);
    }
    private void alert(final String message){
        mSETTINGS.child("notify").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if(snapshot.child("SMS").getValue()!=null) {
                    for (String email : users) {
                        NotifyUser.SMSPUSH(deviceId, email, message);
                    }
                }
                if (snapshot.child("EMAIL").getValue() != null) {
                    for (String email : users) {
                        NotifyUser.emailPUSH(deviceId, email, message);
                    }
                }
                if (snapshot.child("PUSH").getValue() != null) {
                    for (String email : users) {
                        NotifyUser.IIDPUSH(deviceId, email, "智慧機通知", message);
                    }
                    NotifyUser.topicsPUSH(deviceId, memberEmail, "智慧機通知", message);
                }
            }
            @Override
            public void onCancelled(DatabaseError databaseError) {}
        });

        alert.clear();
        alert.put("message","Device:"+message);
        alert.put("timeStamp", ServerValue.TIMESTAMP);
        mAlert.setValue(alert);
        toFBRegister(message);
    }
    private void toFBRegister(String message){
        DatabaseReference mRegister= FirebaseDatabase.getInstance().getReference("/DEVICE/"+deviceId+"/REGISTER");
        String Register = message.split(":")[0].substring(7,11);
        String value =message.split(":")[1];
        if (Register.contains("M")) {
            for (int i=0;i<value.length();i++) {
                register.clear();
                register.put("message", value.substring(i, i+1));
                register.put("timeStamp", ServerValue.TIMESTAMP);
                mRegister.child("M"+(String.format("%04x",(Integer.parseInt(Register.substring(1),16)+i)))).updateChildren(register);
            }
            if (Register.contains("D")){
                register.clear();
                register.put("message", ByteBuffer.wrap(value.getBytes()).getFloat());
                register.put("timeStamp", ServerValue.TIMESTAMP);
                mRegister.child(Register).updateChildren(register);
            }
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

    // eventbus:2.4.0
    @SuppressWarnings("UnusedDeclaration")
    public void onEvent(SocketMessageEvent event) {  //  receive message from eventbus
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
            Intent i;
            i = new Intent(this,MainActivity.class);
            startActivity(i);
            alert("RS232智慧機設定完成!");
        }
    }

    //device online check
    private void deviceOnline(){
        presenceRef = FirebaseDatabase.getInstance().getReference("/DEVICE/"+deviceId+"/connection");
        presenceRef.setValue(true);
        presenceRef.onDisconnect().setValue(null);
        connectedRef = FirebaseDatabase.getInstance().getReference(".info/connected");
        connectedRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                boolean connected = snapshot.getValue(Boolean.class);
                if (connected) {
                    presenceRef.setValue(true);
                }
            }
            @Override
            public void onCancelled(DatabaseError error) {
            }
        });
    }

    private void dataLimit(final DatabaseReference mData,int limit) {
        mData.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                dataCount=(int)(snapshot.getChildrenCount());
            }
            @Override
            public void onCancelled(DatabaseError databaseError) {
            }
        });
        if((dataCount-limit)>0) {
            mData.orderByKey().limitToFirst(dataCount - limit)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override
                        public void onDataChange(DataSnapshot snapshot) {
                            for (DataSnapshot childSnapshot : snapshot.getChildren()) {
                                mData.child(childSnapshot.getKey()).removeValue();
                            }
                        }

                        @Override
                        public void onCancelled(DatabaseError databaseError) {
                        }
                    });
        }
    }
}

