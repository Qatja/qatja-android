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
import java.util.concurrent.ConcurrentHashMap;

import se.goransson.qatja.messages.MQTTConnack;
import se.goransson.qatja.messages.MQTTConnect;
import se.goransson.qatja.messages.MQTTMessage;
import se.goransson.qatja.messages.MQTTPingreq;
import se.goransson.qatja.messages.MQTTPuback;
import se.goransson.qatja.messages.MQTTPubcomp;
import se.goransson.qatja.messages.MQTTPublish;
import se.goransson.qatja.messages.MQTTPubrec;
import se.goransson.qatja.messages.MQTTPubrel;
import se.goransson.qatja.messages.MQTTSuback;
import se.goransson.qatja.messages.MQTTSubscribe;
import se.goransson.qatja.messages.MQTTUnsuback;
import se.goransson.qatja.messages.MQTTUnsubscribe;
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
	private KeepaliveThread mKeepaliveThread;

	/** Keepalive timer */
	private int KEEP_ALIVE_TIMER = 3000;

	/** */
	private Handler mHandler = null;

	/** The local binder object */
	private final QatjaBinder mBinder = new QatjaBinder();

	private String host = "localhost";

	private int port = 1883;

	private String protocolName = null; // For some reason used in interop test
										// suites... only reason it's included
										// here.

	/** Unique identifier for this client */
	private String clientIdentifier;

	private boolean cleanSession = true;

	private boolean willFlag;
	private String willTopic;
	private String willMessage;

	private ConcurrentHashMap<Integer, MQTTMessage> sentPackages = new ConcurrentHashMap<Integer, MQTTMessage>();
	private ConcurrentHashMap<Integer, MQTTMessage> receivedPackages = new ConcurrentHashMap<Integer, MQTTMessage>();

	// PING VARIABLES
	private volatile boolean pingreqSent = false;
	private volatile long pingTime = 0;
	private volatile long lastAction = 0;

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
			Log.d(TAG, "onCreate");

		// TODO something when started

	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (DEBUG)
			Log.d(TAG, "onStartCommand");
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
		if (mKeepaliveThread != null) {
			mKeepaliveThread.cancel();
			mKeepaliveThread = null;
		}

		if (DEBUG)
			Log.d(TAG, "onDestroy");
	}

	@Override
	public IBinder onBind(Intent intent) {
		if (DEBUG)
			Log.d(TAG, "onBind");

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

		connect.setCleanSession(cleanSession);

		connect.setWillFlag(willFlag);
		if (willFlag) {
			connect.setWillTopic(willTopic);
			connect.setWillMessage(willMessage);
		}
		
		if (protocolName != null)
			connect.setProtocolName(protocolName);

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

	public void publish(String topic, String message, byte qos) {
		publish(topic, message.getBytes(), qos);
	}

	/**
	 * Publish a message (byte[]) to a specified topic.
	 * 
	 * @param topic
	 *            the topic
	 * @param payload
	 *            the message
	 * @param qos
	 *            the desired quality of service
	 */
	public void publish(String topic, byte[] payload, byte qos) {
		MQTTPublish publish = new MQTTPublish(topic, payload, qos);
		sendMessage(publish);
	}

	/**
	 * Publish a retained message to a specified topic.
	 * 
	 * @param topic
	 *            the topic
	 * @param message
	 *            the message
	 * @param qos
	 *            the desired quality of service
	 */
	public void publishRetain(String topic, String message, byte qos) {
		publishRetain(topic, message.getBytes(), qos);
	}

	/**
	 * Publish a retained message to a specified topic.
	 * 
	 * @param topic
	 *            the topic
	 * @param payload
	 *            the message to publish
	 * @param qos
	 *            the desired quality of service
	 */
	public void publishRetain(String topic, byte[] payload, byte qos) {
		MQTTPublish publish = new MQTTPublish(topic, payload, qos);
		publish.setRetain(true);
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
		addSentPackage(subscribe);
		sendMessage(subscribe);
	}

	// public synchronized void start() {
	// if (DEBUG)
	// Log.d(TAG, "start");
	//
	// // Cancel any thread attempting to make a connection
	// if (mConnectThread != null) {
	// mConnectThread.cancel();
	// mConnectThread = null;
	// }
	//
	// // Cancel any thread currently running a connection
	// if (mConnectedThread != null) {
	// mConnectedThread.cancel();
	// mConnectedThread = null;
	// }
	//
	// setState(STATE_NONE);
	//
	// // if (host != null)
	// // connect(host, port);
	// }

	private synchronized void sendMessage(MQTTMessage msg) {
		sendMessage(msg, true);
	}

	private synchronized void sendMessage(MQTTMessage msg,
			boolean mustBeConnected) {
		if (DEBUG)
			Log.d(TAG, "Sending message: " + MQTTHelper.decodePackageName(msg));

		if (mustBeConnected) {
			if (getState() == STATE_CONNECTED) {
				try {
					mConnectedThread.write(msg.get());
					lastAction = System.currentTimeMillis();
				} catch (UnsupportedEncodingException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				} catch (MQTTException e) {
					e.printStackTrace();
				}
			} else {
				if (DEBUG)
					Log.d(TAG,
							"Need to be connected to send "
									+ MQTTHelper.decodePackageName(msg));
			}
		} else {
			try {
				mConnectedThread.write(msg.get());
				lastAction = System.currentTimeMillis();
			} catch (UnsupportedEncodingException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			} catch (MQTTException e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * Set MQTT broker host, can be either a domain name or an ip address
	 * 
	 * @param host
	 *            the host
	 */
	public void setHost(String host) {
		this.host = host;
	}

	/**
	 * Set MQTT broker port
	 * 
	 * @param port
	 */
	public void setPort(int port) {
		this.port = port;
	}

	/**
	 * Set client identifier
	 * 
	 * @param clientIdentifier
	 *            the identifier
	 */
	public void setId(String clientIdentifier) {
		this.clientIdentifier = clientIdentifier;
	}

	public void setProtocolName(String protocolName) {
		this.protocolName = protocolName;
	}

	/**
	 * Set clean session
	 * 
	 * @param clean_session
	 */
	public void setCleanSession(boolean clean_session) {
		this.cleanSession = clean_session;
	}

	/**
	 * Set will
	 * 
	 * @param topic
	 *            the will topic
	 * @param message
	 *            the will message
	 */
	public void setWill(String topic, String message) {
		if (topic == null || message == null) {
			willFlag = false;
		} else {
			willTopic = topic;
			willMessage = message;
			willFlag = true;
		}
	}

	/**
	 * Set automatic reconnect
	 * 
	 * @param reconnect
	 */
	public void setReconnect(boolean reconnect) {
		doAutomaticReconnect = reconnect;
	}

	/**
	 * Attempt to reconnect after {@link #RECONNECT_TIMER} milliseconds
	 */
	public void reconnect() {
		reconnectHandler.postDelayed(recoonectRunnable, RECONNECT_TIMER);
	}

	/**
	 * Attempt to reconnect after delay
	 * 
	 * @param milliseconds
	 *            the delay
	 */
	public void reconnect(long milliseconds) {
		reconnectHandler.postDelayed(recoonectRunnable, milliseconds);
	}

	/**
	 * Start a connection attempt
	 */
	public synchronized void connect() {
		if (DEBUG)
			Log.d(TAG, "connect to: " + host);

		// Cancel any thread currently running a ping check
		if (mKeepaliveThread != null) {
			mKeepaliveThread.cancel();
			mKeepaliveThread = null;
		}

		// Cancel any thread attempting to make a connection
		if (getState() == STATE_CONNECTING) {
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

	public synchronized void connect(boolean newSocket) {
		if (DEBUG)
			Log.d(TAG, "connect to: " + host);
		if (newSocket) {
			// Cancel any thread currently running a ping check
			if (mKeepaliveThread != null) {
				mKeepaliveThread.cancel();
				mKeepaliveThread = null;
			}

			// Cancel any thread attempting to make a connection
			if (getState() == STATE_CONNECTING) {
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
		} else {
			setState(STATE_CONNECTING);

			connect(clientIdentifier);
		}
	}

	/**
	 * Service connected
	 * 
	 * @param socket
	 *            the connected socket
	 */
	private synchronized void connected(Socket socket) {
		if (DEBUG)
			Log.d(TAG, "connected, Socket Type:");

		// Cancel any thread currently running a ping check
		if (mKeepaliveThread != null) {
			mKeepaliveThread.cancel();
			mKeepaliveThread = null;
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
		mKeepaliveThread = new KeepaliveThread();
		mKeepaliveThread.start();

		Log.d(TAG, "Sending connect message");

		// Send the connect message
		connect(clientIdentifier);

		// setState(STATE_CONNECTED);
	}

	/**
	 * Indicate that the connection attempt failed and notify the UI Activity.
	 */
	private void connectionFailed(Exception e) {
		if (DEBUG)
			Log.d(TAG, "connectionFailed", e);

		if (doAutomaticReconnect)
			reconnect();

		setState(STATE_CONNECTION_FAILED);
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

	/**
	 * Disconnect the MQTT service
	 */
	public void disconnect() {
		// Cancel any thread currently running a ping check
		if (mKeepaliveThread != null) {
			mKeepaliveThread.cancel();
			mKeepaliveThread = null;
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

	/**
	 * Set the connection state
	 * 
	 * @param state
	 *            the state
	 */
	private synchronized void setState(int state) {
		if (DEBUG)
			Log.d(TAG, "setState() " + mState + " -> " + state);

		if (mState != state) {
			mState = state;

			if (mHandler != null)
				// Give the new state to the Handler so the UI Activity can
				// update
				mHandler.obtainMessage(STATE_CHANGE, state, -1).sendToTarget();
		}
	}

	/**
	 * Get the connection state
	 * 
	 * @return the state
	 */
	public synchronized int getState() {
		return mState;
	}

	/**
	 * Set keep alive timer in milliseconds
	 * 
	 * @param milliseconds
	 *            the time
	 */
	public void setKeepAlive(int milliseconds) {
		KEEP_ALIVE_TIMER = milliseconds;
	}

	/**
	 * Add MQTTMessage to the list of sent packages, used for QoS
	 * {@link #AT_MOST_ONCE} and {@link #EXACTLY_ONCE}
	 * 
	 * @param msg
	 *            the MQTTMessage
	 */
	private void addSentPackage(MQTTMessage msg) {
		sentPackages.put(msg.getPackageIdentifier(), msg);
	}

	/**
	 * Remove MQTTMessage from the list of sent packages, used for QoS
	 * {@link #AT_MOST_ONCE} and {@link #EXACTLY_ONCE}
	 * 
	 * @param msg
	 *            the MQTTMessage
	 */
	private void removeSentPackage(MQTTMessage msg) {
		sentPackages.remove(msg.getPackageIdentifier());
	}

	/**
	 * Add MQTTMessage to the list of received packages, used for QoS
	 * {@link #AT_MOST_ONCE} and {@link #EXACTLY_ONCE}
	 * 
	 * @param msg
	 *            the MQTTMessage
	 */
	private void addReceivedPackage(MQTTMessage msg) {
		receivedPackages.put(msg.getPackageIdentifier(), msg);
	}

	/**
	 * Remove MQTTMessage to the list of received packages, used for QoS
	 * {@link #AT_MOST_ONCE} and {@link #EXACTLY_ONCE}
	 * 
	 * @param msg
	 *            the MQTTMessage
	 */
	private void removeReceivedPackage(MQTTMessage msg) {
		receivedPackages.remove(msg.getPackageIdentifier());
	}

	/**
	 * Make sure to resend packages that haven't been successfully sent. TODO
	 */
	private void resendPackages() {
		for (MQTTMessage msg : sentPackages.values()) {
			if (msg instanceof MQTTPublish) {
				((MQTTPublish) msg).setDup();
			}
			sendMessage(msg);
		}

		for (MQTTMessage msg : receivedPackages.values()) {
			if (msg instanceof MQTTPublish) {
				((MQTTPublish) msg).setDup();
			}
			sendMessage(msg);
		}
	}

	/**
	 * Manages subscription messages
	 * 
	 * @param msg
	 *            the message to handle
	 */
	private synchronized void handleSubscriptions(MQTTMessage msg) {
		if (msg instanceof MQTTSuback) {
			MQTTSuback suback = (MQTTSuback) msg;

			MQTTSubscribe subscribe = (MQTTSubscribe) sentPackages.get(suback
					.getPackageIdentifier());

			String[] topicFilters = subscribe.getTopicFilters();
			byte[] qoss = suback.getPayload();
			for (int i = 0; i < qoss.length; i++) {
				switch (qoss[i]) {
				case SUBSCRIBE_SUCCESS_AT_MOST_ONCE:
				case SUBSCRIBE_SUCCESS_AT_LEAST_ONCE:
				case SUBSCRIBE_SUCCESS_EXACTLY_ONCE:
					Log.d(TAG, "Success subscribing to " + topicFilters[i]);
					break;
				case SUBSCRIBE_FAILURE:
					Log.d(TAG, "Failed subscribing to " + topicFilters[i]);
					break;
				}
			}
		} else if (msg instanceof MQTTUnsuback) {
			MQTTUnsuback unsuback = (MQTTUnsuback) msg;

			MQTTUnsubscribe unsubscribe = (MQTTUnsubscribe) sentPackages
					.get(unsuback.getPackageIdentifier());
			String[] topicFilters = unsubscribe.getTopicFilters();
			for (int i = 0; i < topicFilters.length; i++) {
				Log.d(TAG, "Success unsubscribing to " + topicFilters[i]);
			}
		}

		mHandler.obtainMessage(msg.getType(), -1, -1, msg).sendToTarget();
	}

	private class KeepaliveThread extends Thread {

		public KeepaliveThread() {
			if (DEBUG)
				Log.d(TAG, "CREATE mPingthread ");
		}

		@Override
		public void run() {
			if (DEBUG)
				Log.d(TAG, "BEGIN mPingthread");

			boolean local_pingreqSent = pingreqSent;
			long local_pingTime = pingTime;
			long local_lastAction = lastAction;

			int local_state = mState;

			while (!interrupted()) {
				if (local_state != mState) {
					local_state = mState;

					// if (DEBUG)
					// Log.d(TAG, "Detected change in volatile var: state");
				}

				if (local_state == STATE_CONNECTED) {
					if (local_pingreqSent != pingreqSent) {
						// TODO React to when the listener thread changed the
						// pingreq
						local_pingreqSent = pingreqSent;

						// if (DEBUG)
						// Log.d(TAG,
						// "Detected change in volatile var: pingreq");
					}

					if (local_lastAction != lastAction) {
						// TODO React to when a new action is set
						local_lastAction = lastAction;

						// if (DEBUG)
						// Log.d(TAG,
						// "Detected change in volatile var: lastaction");
					}

					if (local_pingreqSent) {
						// If we're expecting a ping response; detect if we've
						// timed out.
						// if ((System.currentTimeMillis() - local_lastAction) >
						// (KEEP_ALIVE_TIMER + KEEP_ALIVE_GRACE)) {
						if ((System.currentTimeMillis() - local_lastAction) > (KEEP_ALIVE_TIMER + KEEP_ALIVE_TIMER / 2)) {
							// Disconnect?
							// TODO Disconnect
							// if (DEBUG)
							// Log.d(TAG,
							// "Ping time out detected, should disconnect?");
							pingreqSent = false;
						}
					} else {
						// If the last action was too long ago; send a ping
						if ((System.currentTimeMillis() - local_lastAction) > KEEP_ALIVE_TIMER) {
							MQTTPingreq pingreq = new MQTTPingreq();
							sendMessage(pingreq);
							pingreqSent = true;
						}
					}

					// if (local_state != mState)
					// local_state = mState;

				} else {
					// if (DEBUG)
					// Log.d(TAG, "Not connected??");
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
				Log.d(TAG, "BEGIN mConnectThread");

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
				connectionFailed(e);
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

			if (DEBUG)
				Log.d(TAG, "BEGIN mConnectedThread");
		}

		public void run() {
			int len;
			byte[] buffer = new byte[16384];

			// Keep listening to the InputStream while connected
			while (!isInterrupted()) {
				try {
					// Read from the InputStream
					len = mmInStream.read(buffer);

					if (mHandler != null)

						if (len > 0) {
							byte type = MQTTHelper.decode(buffer);

							if (DEBUG)
								Log.d(TAG,
										"Received "
												+ MQTTHelper
														.decodePackageName(type));

							// Handle automatic responses here
							switch (type) {
							case CONNECT:
								// Client should never receive CONNECT message
								break;

							case CONNACK:
								MQTTConnack connack = new MQTTConnack(buffer,
										len);
								switch (connack.getReturnCode()) {
								case CONNECTION_ACCEPTED:
									if (DEBUG)
										Log.d(TAG, "Connected");
									setState(STATE_CONNECTED);
									break;
								case CONNECTION_REFUSED_VERSION:
									if (DEBUG)
										Log.d(TAG,
												"Failed to connect, unaccebtable protocol version");
									setState(STATE_CONNECTION_FAILED);
									break;
								case CONNECTION_REFUSED_IDENTIFIER:
									if (DEBUG)
										Log.d(TAG,
												"Failed to connect, identifier rejected");
									setState(STATE_CONNECTION_FAILED);
									break;
								case CONNECTION_REFUSED_SERVER:
									if (DEBUG)
										Log.d(TAG,
												"Failed to connect, server unavailable");
									setState(STATE_CONNECTION_FAILED);
									break;
								case CONNECTION_REFUSED_USER:
									if (DEBUG)
										Log.d(TAG,
												"Failed to connect, bad username or password");
									setState(STATE_CONNECTION_FAILED);
									break;
								case CONNECTION_REFUSED_AUTH:
									if (DEBUG)
										Log.d(TAG,
												"Failed to connect, not authorized");
									setState(STATE_CONNECTION_FAILED);
									break;
								}
								break;

							case PUBLISH:
								MQTTPublish publish = new MQTTPublish(buffer,
										len);
								switch (publish.getQoS()) {
								case AT_MOST_ONCE:
									// Do nothing
									break;
								case AT_LEAST_ONCE:
									// Send PUBACK
									MQTTPuback puback_ = new MQTTPuback(
											publish.getPackageIdentifier());
									sendMessage(puback_);

									break;
								case EXACTLY_ONCE:
									// Send PUBREC and store message
									MQTTPubrec pubrec_ = new MQTTPubrec(
											publish.getPackageIdentifier());
									addReceivedPackage(pubrec_);
									sendMessage(pubrec_);

									break;
								}
								mHandler.obtainMessage(PUBLISH, mState, -1,
										publish).sendToTarget();
								break;

							case PUBACK:
								MQTTPuback puback = new MQTTPuback(buffer, len);
								removeSentPackage(puback);
								break;

							case PUBREC:
								MQTTPubrec pubrec = new MQTTPubrec(buffer, len);
								int packageIdentifier = pubrec
										.getPackageIdentifier();
								removeSentPackage(pubrec);

								MQTTPubrel pubrel_ = new MQTTPubrel(
										packageIdentifier);
								addSentPackage(pubrel_);
								sendMessage(pubrel_);
								break;

							case PUBREL:
								MQTTPubrel pubrel = new MQTTPubrel(buffer, len);
								removeReceivedPackage(pubrel);

								MQTTPubcomp pubcomp_ = new MQTTPubcomp(
										pubrel.getPackageIdentifier());
								sendMessage(pubcomp_);
								break;

							case PUBCOMP:
								MQTTPubcomp pubcomp = new MQTTPubcomp(buffer,
										len);
								removeReceivedPackage(pubcomp);
								break;

							case SUBSCRIBE:
								// Client doesn't receive this message
								break;

							case SUBACK:
								MQTTSuback suback = new MQTTSuback(buffer, len);
								handleSubscriptions(suback);
								break;

							case UNSUBSCRIBE:
								// Client doesn't receive this message
								break;

							case UNSUBACK:
								MQTTUnsuback unsuback = new MQTTUnsuback(
										buffer, len);
								handleSubscriptions(unsuback);
								removeSentPackage(unsuback);
								break;

							case PINGREQ:
								// Client doesn't receive this message
								pingTime = System.currentTimeMillis();
								break;

							case PINGRESP:
								pingreqSent = false;

								break;
							case DISCONNECT:
								// Client doesn't receive this message
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
			try {
				mmOutStream.write(buffer);
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
