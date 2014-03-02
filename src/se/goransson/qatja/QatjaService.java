package se.goransson.qatja;

/*
 * Copyright (C) 2012 Andreas Göransson, David Cuartielles
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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.Socket;

import se.goransson.qatja.messages.MQTTConnect;
import se.goransson.qatja.messages.MQTTMessage;
import se.goransson.qatja.messages.MQTTPingreq;
import se.goransson.qatja.messages.MQTTPublish;
import se.goransson.qatja.messages.MQTTSubscribe;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

/**
 * A simple local service implementation of an mqtt interface for android
 * applications.
 * 
 * Parts of this class (prominently ConnectThread and ConnectedThread and
 * methods connected to thread handling) are based on code found in Android
 * Sample Application "BluetoothChat" by Google, distributed via Android SDK.
 * 
 * @author andreas
 * 
 */
public class QatjaService extends Service implements MQTTConnectionConstants,
		MQTTConstants {

	private static final boolean DEBUG = true;

	private static final String TAG = "QatjaService";

	/** Current state of the connection */
	private volatile int mState = STATE_NONE;

	/** How long to wait before a reconnect attempt is made */
	private long RECONNECT_TIMER = 5000;

	/** Thread to handle setup of connections */
	private ConnectThread mConnectThread;

	/** Thread to handle communication when connection is established. */
	private ConnectedThread mConnectedThread;

	/** Thread to handle ping requests and responses */
	private PingThread mPingThread;

	/** */
	private Handler mHandler = null;

	/** The local binder object */
	private final QatjaBinder mBinder = new QatjaBinder();

	private String host = "localhost";

	private int port = 1883;

	/** Unique identifier for this client */
	private String clientIdentifier;

	private boolean clean_session = true;

	// PING VARIABLES
	private volatile boolean pingreqSent = false;
	private volatile long pingtime = 0;
	private volatile long lastaction = 0;

	private boolean doAutomaticReconnect = false;
	private Handler reconnectHandler = new Handler();
	private Runnable recoonectRunnable = new Runnable() {
		@Override
		public void run() {
			connect();
		}
	};

	@Override
	public void onCreate() {
		super.onCreate();
		if (DEBUG)
			Log.i(TAG, "onCreate");

		// TODO something when started

	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (DEBUG)
			Log.i(TAG, "onStartCommand");
		// Start a sticky service, the system will try to recreate this service
		// if killed.

		return START_STICKY;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();

		// Kill everything when we stop the service
		// Cancel the thread that completed the connection
		if (mConnectThread != null) {
			mConnectThread.cancel();
			mConnectThread = null;
		}

		// Cancel any thread currently running a connection
		if (mConnectedThread != null) {
			mConnectedThread.cancel();
			mConnectedThread = null;
		}

		// Cancel any thread currently running a ping check
		if (mPingThread != null) {
			mPingThread.cancel();
			mPingThread = null;
		}

		if (DEBUG)
			Log.i(TAG, "onDestroy");
	}

	@Override
	public IBinder onBind(Intent intent) {
		if (DEBUG)
			Log.i(TAG, "onBind");

		return mBinder;
	}

	/**
	 * 
	 * @author andreas
	 * 
	 */
	public class QatjaBinder extends Binder {
		public QatjaService getService() {
			return QatjaService.this;
		}
	}

	/**
	 * Set Handler that receives all service state messages and mqtt messages
	 * 
	 * @param handler
	 *            The listener
	 */
	public void setHandler(Handler handler) {
		mHandler = handler;
	}

	/**
	 * Send a CONNECT message to the server.
	 */
	private void connect(String clientIdentifier) {
		MQTTConnect connect = new MQTTConnect(clientIdentifier);
		sendMessage(connect, false);
	}


	/**
	 * Publish a message (String) to a specified topic.
	 * 
	 * @param topic
	 *            Topic to publish to
	 * @param message
	 *            Message to publish
	 */
	public void publish(String topic, String message) {
		publish(topic, message.getBytes());
	}

	/**
	 * Publish a message (byte[]) to a specified topic.
	 * 
	 * @param topic
	 *            Topic to publish to
	 * @param message
	 *            Message to publish
	 */
	public void publish(String topic, byte[] payload) {
		publish(topic, payload, AT_MOST_ONCE);
	}

	/**
	 * Publish a message (byte[]) to a specified topic.
	 * 
	 * @param topic
	 *            Topic to publish to
	 * @param message
	 *            Message to publish
	 * @param retain
	 *            Should the message be retained on server? True or false
	 */
	public void publish(String topic, byte[] payload, byte qos) {
		MQTTPublish publish = new MQTTPublish(topic, payload, qos);
		sendMessage(publish);
	}

	/**
	 * Subscribe to a topic with Quality of Service (QoS) level
	 * {@link #AT_MOST_ONCE}
	 * 
	 * @param topic
	 *            Topic to subscribe to
	 */
	public void subscribe(String topic) {
		String[] topics = { topic };
		subscribe(topics);
	}

	/**
	 * Subscribe to multiple topics with quality of service
	 * {@link #AT_MOST_ONCE}
	 * 
	 * @param topics
	 *            Topics to subscribe to
	 */
	public void subscribe(String[] topics) {
		byte[] qoss = new byte[topics.length];
		for (int i = 0; i < qoss.length; i++)
			qoss[i] = AT_MOST_ONCE;
		subscribe(topics, qoss);
	}

	/**
	 * Subscribe to a topic
	 * 
	 * @param topic
	 *            Topic to subscribe to
	 * @param qos
	 *            Quality of service, can be {@link #AT_MOST_ONCE},
	 *            {@link #AT_LEAST_ONCE}, or {@link #EXACTLY_ONCE}.
	 */
	public void subscribe(String topic, byte qos) {
		String[] topics = { topic };
		byte[] qoss = { qos };
		subscribe(topics, qoss);
	}
	
	/**
	 * Subscribe to multiple topics
	 * 
	 * @param topic
	 *            Topic to subscribe to
	 * @param qos
	 *            Quality of service, can be {@link #AT_MOST_ONCE},
	 *            {@link #AT_LEAST_ONCE}, or {@link #EXACTLY_ONCE}.
	 */
	public void subscribe(String[] topics, byte[] qoss) {
		MQTTSubscribe subscribe = new MQTTSubscribe(topics, qoss);
		sendMessage(subscribe);
	}

//	public synchronized void start() {
//		if (DEBUG)
//			Log.d(TAG, "start");
//
//		// Cancel any thread attempting to make a connection
//		if (mConnectThread != null) {
//			mConnectThread.cancel();
//			mConnectThread = null;
//		}
//
//		// Cancel any thread currently running a connection
//		if (mConnectedThread != null) {
//			mConnectedThread.cancel();
//			mConnectedThread = null;
//		}
//
//		setState(STATE_NONE);
//
//		// if (host != null)
//		// connect(host, port);
//	}
	
	private void sendMessage(MQTTMessage msg) {
		sendMessage(msg, true);
	}

	private synchronized void sendMessage(MQTTMessage msg, boolean mustBeConnected) {
		if (mustBeConnected) {
			if (getState() != STATE_CONNECTED) {
				try {
					mConnectedThread.write(msg.get());
				} catch (UnsupportedEncodingException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				} catch (MQTTException e) {
					e.printStackTrace();
				}
			} else {
				if (DEBUG)
					Log.i(TAG, "Need to be connected to send messages");
			}
		} else {
			try {
				mConnectedThread.write(msg.get());
			} catch (UnsupportedEncodingException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			} catch (MQTTException e) {
				e.printStackTrace();
			}
		}
	}

	public void setHost(String host) {
		this.host = host;
	}

	public void setPort(int port) {
		this.port = port;
	}

	public void setId(String uid) {
		this.clientIdentifier = uid;
	}

	public void setCleanSession(boolean clean_session) {
		this.clean_session = clean_session;
	}

	public void setReconnect(boolean reconnect) {
		doAutomaticReconnect = reconnect;
	}

	public void reconnect() {
		reconnectHandler.postDelayed(recoonectRunnable, RECONNECT_TIMER);
	}

	public void reconnect(long millis) {
		reconnectHandler.postDelayed(recoonectRunnable, millis);
	}

	public synchronized void connect() {
		if (DEBUG)
			Log.d(TAG, "connect to: " + host);

		// Cancel any thread currently running a ping check
		if (mPingThread != null) {
			mPingThread.cancel();
			mPingThread = null;
		}

		// Cancel any thread attempting to make a connection
		if (mState == STATE_CONNECTING) {
			if (mConnectThread != null) {
				mConnectThread.cancel();
				mConnectThread = null;
			}
		}

		// Cancel any thread currently running a connection
		if (mConnectedThread != null) {
			mConnectedThread.cancel();
			mConnectedThread = null;
		}

		// Start the thread to connect with the given device
		mConnectThread = new ConnectThread(host, port);
		mConnectThread.start();

		setState(STATE_CONNECTING);
	}

	public synchronized void connected(Socket socket) {
		if (DEBUG)
			Log.d(TAG, "connected, Socket Type:");

		// Cancel any thread currently running a ping check
		if (mPingThread != null) {
			mPingThread.cancel();
			mPingThread = null;
		}

		// Cancel the thread that completed the connection
		if (mConnectThread != null) {
			mConnectThread.cancel();
			mConnectThread = null;
		}

		// Cancel any thread currently running a connection
		if (mConnectedThread != null) {
			mConnectedThread.cancel();
			mConnectedThread = null;
		}

		// Start the thread to manage the connection and perform transmissions
		mConnectedThread = new ConnectedThread(socket);
		mConnectedThread.start();

		// Start the ping check thread
		mPingThread = new PingThread();
		mPingThread.start();

		// Send the connect message
		connect(clientIdentifier);

		setState(STATE_CONNECTED);

		// Set the current time as the last action
		lastaction = System.currentTimeMillis();
	}

	/**
	 * Indicate that the connection attempt failed and notify the UI Activity.
	 */
	private void connectionFailed() {
		if (DEBUG)
			Log.d(TAG, "connectionFailed");

		if (doAutomaticReconnect)
			reconnect();

		if (mHandler != null)
			// Give the new state to the Handler so the UI Activity can update
			mHandler.obtainMessage(STATE_CHANGE, STATE_CONNECTION_FAILED, -1)
					.sendToTarget();
	}

	/**
	 * Indicate that the connection was lost and notify the UI Activity.
	 */
	private void connectionLost() {
		if (DEBUG)
			Log.d(TAG, "connectionLost");

		if (doAutomaticReconnect)
			reconnect();
	}

	public void disconnect() {
		// Cancel any thread currently running a ping check
		if (mPingThread != null) {
			mPingThread.cancel();
			mPingThread = null;
		}

		// Cancel the thread that completed the connection
		if (mConnectThread != null) {
			mConnectThread.cancel();
			mConnectThread = null;
		}

		// Cancel any thread currently running a connection
		if (mConnectedThread != null) {
			mConnectedThread.cancel();
			mConnectedThread = null;
		}

		setState(STATE_NONE);
	}

	private synchronized void setState(int state) {
		if (DEBUG)
			Log.d(TAG, "setState() " + mState + " -> " + state);

		mState = state;

		if (mHandler != null)
			// Give the new state to the Handler so the UI Activity can update
			mHandler.obtainMessage(STATE_CHANGE, state, -1).sendToTarget();
	}

	public synchronized int getState() {
		return mState;
	}

	public void setKeepAlive(int milliseconds) {
		KEEP_ALIVE_TIMER = milliseconds;
	}

	private int KEEP_ALIVE_TIMER = 10000;

	private class PingThread extends Thread {

		private static final int KEEP_ALIVE_GRACE = 2000;

		public PingThread() {
			if (DEBUG)
				Log.d(TAG, "CREATE mPingthread ");

			// Set the current time as the last action
			lastaction = System.currentTimeMillis();
		}

		@Override
		public void run() {
			if (DEBUG)
				Log.i(TAG, "BEGIN mPingthread");

			boolean local_pingreq = pingreqSent;
			long local_pingtime = pingtime;
			long local_lastaction = lastaction;

			int local_state = mState;

			while (!interrupted()) {
				if (local_state != mState) {
					local_state = mState;

					if (DEBUG)
						Log.i(TAG, "Detected change in volatile var: state");
				}

				if (local_state == STATE_CONNECTED) {
					if (local_pingreq != pingreqSent) {
						// TODO React to when the listener thread changed the
						// pingreq
						local_pingreq = pingreqSent;

						if (DEBUG)
							Log.i(TAG,
									"Detected change in volatile var: pingreq");
					}

					if (local_lastaction != lastaction) {
						// TODO React to when a new action is set
						local_lastaction = lastaction;

						if (DEBUG)
							Log.i(TAG,
									"Detected change in volatile var: lastaction");
					}

					if (local_pingreq) {
						// If we're expecting a ping response; detect if we've
						// timed out.
						if ((System.currentTimeMillis() - local_lastaction) > (KEEP_ALIVE_TIMER + KEEP_ALIVE_GRACE)) {
							// Disconnect?
							// TODO Disconnect
							// if (DEBUG)
							Log.i(TAG,
									"Ping time out detected, should disconnect?");
							pingreqSent = false;
						}
					} else {
						// If the last action was too long ago; send a ping
						if ((System.currentTimeMillis() - local_lastaction) > KEEP_ALIVE_TIMER) {
							MQTTPingreq pingreq = new MQTTPingreq();
							sendMessage(pingreq);
							pingreqSent = true;
						}
					}

					// if (local_state != mState)
					// local_state = mState;

				} else {
					// if (DEBUG)
					// Log.i(TAG, "Not connected??");
					Thread.currentThread().interrupt();
				}

				if (!interrupted()) {
					try {
						Thread.sleep(500);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}

			}
		}

		public void cancel() {
			// TODO do we need to do something?
		}

	}

	private class ConnectThread extends Thread {
		private static final String TAG = "ConnectThread";

		private final Socket mmSocket;

		/** Timeout of connection attempts (ms) */
		private int timeout = 3000;

		private InetSocketAddress remoteAddr;

		public ConnectThread(String host, int port) {
			if (DEBUG)
				Log.d(TAG, "CREATE mConnectThread ");

			Socket tmp = new Socket();

			mmSocket = tmp;
		}

		public void run() {
			if (DEBUG)
				Log.i(TAG, "BEGIN mConnectThread");

			setName("ConnectThread");

			remoteAddr = new InetSocketAddress(host, port);

			// Make a connection to the Socket
			try {
				// This is a blocking call and will only return on a
				// successful connection or an exception
				mmSocket.connect(remoteAddr, timeout);
			} catch (IOException e) {
				// Close the socket
				try {
					mmSocket.close();
				} catch (IOException e2) {
					Log.e(TAG,
							"unable to close() socket during connection failure",
							e2);
				}
				connectionFailed();
				return;
			}

			// Reset the ConnectThread because we're done
			synchronized (QatjaService.this) {
				mConnectThread = null;
			}

			// Start the connected thread
			connected(mmSocket);
		}

		public void cancel() {
			try {
				mmSocket.close();
			} catch (IOException e) {
				Log.e(TAG, "close() of connect socket failed", e);
			}
		}
	}

	/**
	 * This thread runs during a connection with a remote device. It handles all
	 * incoming and outgoing transmissions.
	 */
	private class ConnectedThread extends Thread {
		private final Socket mmSocket;
		private final InputStream mmInStream;
		private final OutputStream mmOutStream;

		public ConnectedThread(Socket socket) {
			if (DEBUG)
				Log.d(TAG, "CREATE mConnectedThread");

			mmSocket = socket;
			InputStream tmpIn = null;
			OutputStream tmpOut = null;

			// Get the Socket input and output streams
			try {
				tmpIn = socket.getInputStream();
				tmpOut = socket.getOutputStream();
			} catch (IOException e) {
				Log.e(TAG, "temp sockets not created", e);
			}

			mmInStream = tmpIn;
			mmOutStream = tmpOut;
		}

		public void run() {
			if (DEBUG)
				Log.i(TAG, "BEGIN mConnectedThread");

			byte[] buffer = new byte[16384];
			int bytes;

			// Keep listening to the InputStream while connected
			while (!isInterrupted()) {
				try {
					// Read from the InputStream
					bytes = mmInStream.read(buffer);

					if (mHandler != null)
						// Send the obtained bytes to the UI Activity
						// mHandler.obtainMessage(MQTT_RAW_READ, bytes, -1,
						// buffer)
						// .sendToTarget();

						if (bytes > 0) {
							byte type = MQTTHelper.decode(buffer);

//							// Share the recieved msg type back to activity
//							if (mHandler != null)
//								mHandler.obtainMessage(msg.type, msg)
//										.sendToTarget();

							// Handle automatic responses here
							switch (type) {
							case PUBLISH:
								// No need to act on normal PUBLISH messages.
								break;
							case PUBACK:
								break;
							case PUBREC:
								break;
							case PUBREL:
								break;
							case PUBCOMP:
								break;
							case SUBSCRIBE:
								// The client shouldn't receive any SUBSCRIBE
								// messages.
								break;
							case SUBACK:
								break;
							case UNSUBSCRIBE:
								break;
							case UNSUBACK:
								break;
							case PINGREQ:
								// The client shouldn't receive any PINGREQ
								// messages.
								break;
							case PINGRESP:
								// TODO PINGREQ was successful, connections
								// still
								// alive.
								pingreqSent = false;

								if (DEBUG)
									Log.i(TAG, "Got ping response");

								break;
							case DISCONNECT:
								// TODO close all threads when receiving the
								// DISCONNECT message.
								break;
							}
						}

				} catch (IOException e) {
					if (DEBUG)
						Log.e(TAG, "disconnected", e);

					connectionLost();

					disconnect();

					break;
				}
			}
		}

		/**
		 * Write to the connected OutStream.
		 * 
		 * @param buffer
		 *            The bytes to write
		 */
		public synchronized void write(byte[] buffer) {
			Log.i(TAG, "write()");
			Log.i(TAG, new String(buffer));
			try {
				mmOutStream.write(buffer);

				if (mHandler != null)
					// Share the sent message back to the UI Activity
					mHandler.obtainMessage(MQTT_RAW_PUBLISH, -1, -1, buffer)
							.sendToTarget();

				lastaction = System.currentTimeMillis();
			} catch (IOException e) {
				Log.e(TAG, "Exception during write", e);

				disconnect();

				if (doAutomaticReconnect)
					reconnect();
			}
		}

		public void cancel() {
			try {
				mmSocket.close();
			} catch (IOException e) {
				Log.e(TAG, "close() of connect socket failed", e);
			}
		}
	}
}
