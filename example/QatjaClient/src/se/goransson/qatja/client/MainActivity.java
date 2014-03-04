package se.goransson.qatja.client;


import java.lang.ref.WeakReference;

import javax.net.ssl.HandshakeCompletedListener;

import se.goransson.qatja.MQTTConnectionConstants;
import se.goransson.qatja.MQTTConstants;
import se.goransson.qatja.QatjaService;
import se.goransson.qatja.QatjaService.QatjaBinder;
import se.goransson.qatja.messages.MQTTPublish;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.widget.Toast;

/**
 * Copyright 2014 Andreas Goransson
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 * 
 * @author andreas
 * 
 */
public class MainActivity extends Activity implements MQTTConnectionConstants,
		MQTTConstants {

	protected static final String TAG = "QatjaClient";

	QatjaService client;

	boolean isBound;

	private Controller mController;
	
	Handler mHandler = new Handler(new MQTTCallback());
	
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.activity_main);

		mController = new Controller(this);
	}

	@Override
	protected void onResume() {
		mController.showConnectionFragment();
		super.onResume();
	}

	@Override
	protected void onStart() {
		super.onStart();
		Intent service = new Intent(MainActivity.this, QatjaService.class);
		bindService(service, connection, Context.BIND_AUTO_CREATE);
	}

	@Override
	protected void onStop() {
		super.onStop();
		unbindService(connection);
	}

	private ServiceConnection connection = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName name, IBinder binder) {
			client = ((QatjaBinder) binder).getService();
			isBound = true;

			client.setHandler(mHandler);
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			isBound = false;
		}
	};
	
	private class MQTTCallback implements Handler.Callback {

		@Override
		public boolean handleMessage(Message msg) {
			switch (msg.what) {
			case STATE_CHANGE:
				switch (msg.arg1) {
				case STATE_NONE:
					Toast.makeText(MainActivity.this, "Not connected",
							Toast.LENGTH_SHORT).show();
					return true;
					
				case STATE_CONNECTING:
					Toast.makeText(MainActivity.this, "Trying to connect...",
							Toast.LENGTH_SHORT).show();
					return true;
					
				case STATE_CONNECTED:
					Toast.makeText(MainActivity.this, "Yay! Connected!",
							Toast.LENGTH_SHORT).show();
					mController.showSubscribeFragment();
					return true;
					
				case STATE_CONNECTION_FAILED:
					Toast.makeText(MainActivity.this, "Connection failed",
							Toast.LENGTH_SHORT).show();
					return true;
					
				}
				return true;

			case PUBLISH:
				MQTTPublish publish = (MQTTPublish) msg.obj;
				String topic = publish.getTopicName();
				byte[] payload = publish.getPayload();
				String text = new String(payload);
				mController.appendMessage(text);
				return true;
				
			default:
				return false;
			}
		}
	};

	protected void connect(String host, String clientIdentifier) {
		// Default host is test.mosquitto.org (you should change this!)
		client.setHost(host);

		// Default mqtt port is 1883
		client.setPort(1883);

		// Set a unique id for this client-broker combination
		client.setId(clientIdentifier);
		
		// Set keep alive time
		client.setKeepAlive(3000);
		
		// Open the connection to the MQTT server
		client.connect();
	}

	protected void publish(String topic, String message) {
		client.publish(topic, message);
	}

	protected void subscribe(String topic) {
		client.subscribe(topic, AT_MOST_ONCE);
	}
}
