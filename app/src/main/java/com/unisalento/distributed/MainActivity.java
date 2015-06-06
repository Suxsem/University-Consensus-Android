package com.unisalento.distributed;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.Arrays;


public class MainActivity extends ActionBarActivity {

    private Messenger service = null;
    private final Messenger serviceHandler = new Messenger(new ServiceHandler());
    private IntentFilter intentFilter = null;
    private PushReceiver pushReceiver;

    private final static String MESSAGE = "com.unisalento.distributed.Message";
    private final static String CONENCTED = "com.unisalento.distributed.Connected";
    private final static String DISCONNECTED = "com.unisalento.distributed.Disconnected";

    private int connected = -1;

    private TextView stateView;
    private TextView iterView;
    private TextView onlineView;


    //consensus variables node c

    public final static String my_label = "d";
    private final static String[] in_label = {"c","e"};
    private int in_n;
    private float[] data;
    private String[] sync;
    private float state;
    private float input;
    private float currentTemp;

    public class PushReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(MESSAGE)) {

                String message = intent.getStringExtra(MqttService.MESSAGE);
                String topic = intent.getStringExtra(MqttService.TOPIC);
                int last_slash_pos = topic.lastIndexOf("/");
                String sender = topic.substring(last_slash_pos + 1, last_slash_pos + 2);
                String type = topic.substring(last_slash_pos + 3);
                for (int i = 0; i < in_n; i++) {
                    if (in_label[i].equals(sender)) {
                        if (type.equals("d"))
                            data[i] = Float.parseFloat(message);
                        else if (type.equals("s"))
                            sync[i] = message;
                    }
                }

            } else if (intent.getAction().equals(CONENCTED) && connected != 1) {
                connected = 1;
                subscribe(true);
            } else if (intent.getAction().equals(DISCONNECTED) && connected != 0) {
                connected = 0;
                subscribe(false);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        stateView = (TextView) findViewById(R.id.state);
        iterView = (TextView) findViewById(R.id.iteration);
        onlineView = (TextView) findViewById(R.id.test);

        intentFilter = new IntentFilter();
        intentFilter.addAction(MESSAGE);
        intentFilter.addAction(CONENCTED);
        intentFilter.addAction(DISCONNECTED);
        pushReceiver = new PushReceiver();
        registerReceiver(pushReceiver, intentFilter, null, null);
    }

    Thread consensusThread;
    class ConsensusThread extends Thread {
        int in_n_online;
        int skip = 0;

        @Override
        public void run() {

            try {

                while (connected < 1)
                    if (Thread.interrupted())
                        throw new InterruptedException();

                in_n = in_label.length;
                data = new float[in_n];
                sync = new String[in_n];
                Arrays.fill(sync, "F");
                input = currentTemp;
                state = input;

                publish(my_label + "_s", "B");

                Thread.sleep(10000);

                publish(my_label + "_d", String.valueOf(state));
                publish(my_label + "_s", "R");

                while(true) {

                    if (Thread.interrupted())
                        throw new InterruptedException();

                    in_n_online = 0;
                    for (int i = 0; i < in_n; i++) {
                        if (sync[i].equals("R"))
                            in_n_online++;
                    }
                    float weight = 1f / (in_n_online + 1);

                    // newState: x(t+h)
                    float newState = state;
                    for (int i = 0; i < in_n; i++) {
                        if (sync[i].equals("R"))
                            newState += weight * (data[i] - state);
                    }
                    // delta r: newInput - input (=dato letto)
                    float newInput = currentTemp;
                    state = newState + newInput - input;
                    input = newInput;

                    publish(my_label + "_d", String.valueOf(state));
                    publish(my_label + "_s", "R");

                    for (int i = 0; i < in_n; i++) {
                        if (sync[i].equals("R"))
                            sync[i] = "B";
                    }

                    skip++;

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            stateView.setText(String.valueOf(state));
                            iterView.setText(String.valueOf(skip));
                            onlineView.setText(String.valueOf(in_n_online));
                        }
                    });

                    Thread.sleep(500);

                    for (int i = 0; i < in_n; i++) {
                        if (sync[i].equals("B"))
                            i = -1;
                    }

                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        startService(new Intent(this, MqttService.class));
        bindService(new Intent(this, MqttService.class), serviceConnection, 0);
        registerReceiver(tempReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        registerReceiver(pushReceiver, intentFilter);
        consensusThread = new ConsensusThread();
        consensusThread.start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (consensusThread != null)
            consensusThread.interrupt();

        publish(my_label + "_s", "F");

        subscribe(false);
        connected = -1;
        unregisterReceiver(tempReceiver);
        unregisterReceiver(pushReceiver);
        unbindService(serviceConnection);
        finish();
    }

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName arg0, IBinder binder) {
            service = new Messenger(binder);
            Bundle bundle = new Bundle();
            //bundle.putSerializable(MqttService.CLASSNAME, MainActivity.class);
            bundle.putCharSequence(MqttService.INTENTNAME, MESSAGE);
            bundle.putCharSequence(MqttService.CONNECTEDNAME, CONENCTED);
            bundle.putCharSequence(MqttService.DISCONNECTEDNAME, DISCONNECTED);
            Message msg = Message.obtain(null, MqttService.REGISTER);
            msg.setData(bundle);
            msg.replyTo = serviceHandler;
            try {
                service.send(msg);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
        }
    };

    private void subscribe(boolean subscribe) {
        Bundle bundle;
        Message msg;
        try {
            for (int i = 0; i < in_label.length; i++) {
                bundle = new Bundle();
                bundle.putCharSequence(MqttService.TOPIC, in_label[i] + "_d");
                bundle.putInt(MqttService.QOS, 2);
                msg = Message.obtain(null, subscribe ? MqttService.SUBSCRIBE : MqttService.UNSUBSCRIBE);
                msg.setData(bundle);
                service.send(msg);

                bundle = new Bundle();
                bundle.putCharSequence(MqttService.TOPIC, in_label[i] + "_s");
                bundle.putInt(MqttService.QOS, 2);
                msg = Message.obtain(null, subscribe ? MqttService.SUBSCRIBE : MqttService.UNSUBSCRIBE);
                msg.setData(bundle);
                service.send(msg);

            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void publish(String topic, String message) {
        Bundle bundle = new Bundle();
        bundle.putCharSequence(MqttService.TOPIC, topic);
        bundle.putCharSequence(MqttService.MESSAGE, message);
        bundle.putInt(MqttService.QOS, 2);
        bundle.putBoolean(MqttService.RETAIN, true);
        Message msg = Message.obtain(null, MqttService.PUBLISH);
        msg.setData(bundle);
        try {
            service.send(msg);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    class ServiceHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MqttService.REGISTER:
                    try {
                        service.send(Message.obtain(null, MqttService.CHECKCONNECTIVITY));
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                    break;
                default:
                    super.handleMessage(msg);
                    return;
            }
        }
    }

    private BroadcastReceiver tempReceiver = new BroadcastReceiver(){
        @Override
        public void onReceive(Context arg0, Intent intent) {
            currentTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
        }
    };

}