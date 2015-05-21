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
import android.widget.TextView;


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
    private final static int in_n = 2;
    private int in_n_online = 0;
    public final static String my_label = "c";
    private final static String[] in_label = {"a", "d"};
    private float[] data = new float[in_n];
    private boolean[] online = new boolean[in_n];
    private float state = 0;
    private float currentTemp = -274;

    public class PushReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(MESSAGE)) {

                String message = intent.getStringExtra(MqttService.MESSAGE);
                String topic = intent.getStringExtra(MqttService.TOPIC);
                int last_slash_pos = topic.lastIndexOf("/");
                String sender = topic.substring(last_slash_pos + 1);
                for (int i = 0; i < in_n; i++) {
                    if (in_label[i].equals(sender)) {
                        data[i] = Float.parseFloat(message);
                        if (data[i] > -274 && !online[i]) {
                            online[i] = true;
                            in_n_online++;
                        } else if (data[i] == -274 && online[i]) {
                            online[i] = false;
                            in_n_online--;
                        }
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

        stateView = (TextView) findViewById(R.id.state);
        iterView = (TextView) findViewById(R.id.iteration);
        onlineView = (TextView) findViewById(R.id.test);

        registerReceiver(tempReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        intentFilter = new IntentFilter();
        intentFilter.addAction(MESSAGE);
        intentFilter.addAction(CONENCTED);
        intentFilter.addAction(DISCONNECTED);
        pushReceiver = new PushReceiver();
        registerReceiver(pushReceiver, intentFilter, null, null);
    }

    Thread consensusThread;
    class ConsensusThread extends Thread {
        private int skip = -1;

        @Override
        public void run() {

            try {
                while(true) {

                    if (Thread.interrupted())
                        throw new InterruptedException();

                    sleep(500);

                    if (currentTemp == -274)
                        continue;

                    skip++;

                    if (skip % 10 != 0)
                        continue;

                    if (skip % 300 == 0) {
                        state = currentTemp / 10;
                    }

                    //algoritmo di consenso

                    Bundle bundle = new Bundle();
                    bundle.putCharSequence(MqttService.TOPIC, "test/distributed/c");
                    bundle.putCharSequence(MqttService.MESSAGE, String.valueOf(state));
                    bundle.putInt(MqttService.QOS, 2);
                    bundle.putBoolean(MqttService.RETAIN, true);
                    Message msg = Message.obtain(null, MqttService.PUBLISH);
                    msg.setData(bundle);
                    try {
                        service.send(msg);
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }

                    float weight = 1f / (in_n_online + 1);
                    float old_state = state;
                    state = 0;
                    for (int i = 0; i < in_n; i++) {
                        if (online[i])
                            state = state + weight * data[i];
                    }
                    state = state + weight * old_state;

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            stateView.setText(String.valueOf(state));
                            iterView.setText(String.valueOf(skip % 100 / 10));
                            onlineView.setText(String.valueOf(in_n_online));
                        }
                    });

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
        registerReceiver(pushReceiver, intentFilter);
        consensusThread = new ConsensusThread();
        consensusThread.start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (consensusThread != null)
            consensusThread.interrupt();
        Bundle bundle = new Bundle();
        bundle.putCharSequence(MqttService.TOPIC, "test/distributed/c");
        bundle.putCharSequence(MqttService.MESSAGE, "-274");
        bundle.putInt(MqttService.QOS, 2);
        bundle.putBoolean(MqttService.RETAIN, true);
        Message msg = Message.obtain(null, MqttService.PUBLISH);
        msg.setData(bundle);
        try {
            service.send(msg);
        } catch (RemoteException e) {
            e.printStackTrace();
        }

        subscribe(false);
        connected = -1;
        unregisterReceiver(pushReceiver);
        unbindService(serviceConnection);
    }

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName arg0, IBinder binder) {
            service = new Messenger(binder);
            Bundle data = new Bundle();
            //data.putSerializable(MqttService.CLASSNAME, MainActivity.class);
            data.putCharSequence(MqttService.INTENTNAME, MESSAGE);
            data.putCharSequence(MqttService.CONNECTEDNAME, CONENCTED);
            data.putCharSequence(MqttService.DISCONNECTEDNAME, DISCONNECTED);
            Message msg = Message.obtain(null, MqttService.REGISTER);
            msg.setData(data);
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
        Bundle data;
        Message msg;
        try {
            for (int i = 0; i < in_label.length; i++) {
                data = new Bundle();
                data.putCharSequence(MqttService.TOPIC, "test/distributed/" + in_label[i]);
                data.putInt(MqttService.QOS, 2);
                msg = Message.obtain(null, subscribe ? MqttService.SUBSCRIBE : MqttService.UNSUBSCRIBE);
                msg.setData(data);
                service.send(msg);
            }
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
            currentTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
        }
    };

}