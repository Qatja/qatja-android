Qatja Android library
==========

**This is the old Eclipse project repo**

This library creates a new service called QatjaService.java which allows for a easy connection to any MQTT server running standard version 3.1.1

## Installation (Eclipse only)

1. Clone library `git clone https://github.com/Qatja/android.git`
2. Add the qatja-android Eclipse project to your workspace
3. Make sure you have correct Android SDK installed (you can change this SDK version to one that suits your project also)

## Create MQTT enabled Android project

1. Create new Android application
`File > New > Other > Android project`

2. Add the mqtt4android library to your project
Open the dialog `File > Properties > Android` and then click `Add...` and select the mqtt4android library.

3. Add the MQTTService service to your manifest
```xml
<service android:name="se.goransson.qatja.QatjaService" ></service> 
```

4. The library uses a Handler to communicate to your UI for safety reasons, create a handler that listens for MQTT state and messages.
```java
Handler mHandler = new Handler(new MQTTCallback());

private class MQTTCallback implements Handler.Callback {
  @Override
  public boolean handleMessage(Message msg) {
    switch (msg.what) {
      case STATE_CHANGE:
        switch (msg.arg1) {
        case STATE_NONE:
          Toast.makeText(MainActivity.this, "Not connected", Toast.LENGTH_SHORT).show();
          return true;
        case STATE_CONNECTING:
          Toast.makeText(MainActivity.this, "Trying to connect...", Toast.LENGTH_SHORT).show();
          return true;
       case STATE_CONNECTED:
         Toast.makeText(MainActivity.this, "Yay! Connected!", Toast.LENGTH_SHORT).show();
         return true;
       case STATE_CONNECTION_FAILED:
         Toast.makeText(MainActivity.this, "Connection failed", Toast.LENGTH_SHORT).show();
         return true;		
      }
      return true;
    case PUBLISH:
      MQTTPublish publish = (MQTTPublish) msg.obj;
      String topic = publish.getTopicName();
      byte[] payload = publish.getPayload();
      return true;
    default:
      return false;
    }
  }
};
```

5. Create a service connection instance. Also, set your previously created handler as the service handler
```java
private ServiceConnection connection = new ServiceConnection() {
  @Override
  public void onServiceConnected(ComponentName name, IBinder binder) {
    client = ((QatjaBinder) binder).getService();
    isBound = true;
    
    mqtt.setHandler(mHandler);
  }

  @Override
  public void onServiceDisconnected(ComponentName name) {
    isBound = false;
  }
};
```

6. Bind to the service when application starts.
```java
@Override
protected void onStart() {
  super.onStart();
  Intent service = new Intent(MainActivity.this, QatjaService.class);
  bindService(service, connection, Context.BIND_AUTO_CREATE);
}
```

7. Unbind the service when application is stopped.
```java
@Override
protected void onStop() {
  super.onStop();
  unbindService(connection);
}
```

## Example usage
The following small examples show how basic usage for MQTT.

## Connect to MQTT Server
Connecting to an MQTT server can only be done once the service connection has been established. Therefore you're recommended to attempt the connection in the service connection object after the Handler has been linked.
You can of course issue the connection elsewhere, as long as you make sure the service connection is "alive and kicking".

```java
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
```

## Subscribe to topic
There are multiple ways of subscribing to a topic.
```java
// Subscribe to a topic with Quality of Service AT_MOST_ONCE
mqtt.subscribe(topic);
// Subscribe to a topic with specified Quality of Service ()
mqtt.subscribe(topic, EXACTLY_ONCE);
// Subscribe to multiple topics (String[]) with Quality of Service AT_MOST_ONCE
mqtt.subscribe(topics[]);
// Subscribe to multiple topics (String[]) with specified quality of service (byte[]) for each topic
mqtt.subscribe(topics[], qoss[]);
```

## Publish to topic
There are multiple ways of publishing to an MQTT topic.
```java
// Publish a String message
mqtt.publish("mytopic", "my message");
// Publish a byte[] message
mqtt.publish("mytopic", message[]);
// Publish a byte[] message with RETAIN flag set
mqtt.publish("mytopic", message[], true);
```
