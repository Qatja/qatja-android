Qatja Android library
==========

This library creates a new service called QatjaService.java which allows for a easy connection to any MQTT server running standard version 3.1.1

## Installation

This library is not fully available from JCenter yet, but will be in future. Until then, follow these steps to use this library in your Android project.

1. Add the bintray maven repository to your project level `build.gradle`

```
[...]

allprojects {
    repositories {
        jcenter()

        maven {
            url 'https://dl.bintray.com/wetcat/Qatja/'
        }
    }
}

[...]
```

2. Add the dependency to your app level `build.gradle`

```
[...]

dependencies {
    [...]
    
    compile 'se.wetcat.qatja:qatja-android:0.1.2'
}

[...]
```

## Enabling MQTT in your project

1. Add the MQTT service to your manifest

```xml
<service
    android:name="se.wetcat.qatja.android.QatjaService"
    android:exported="false"/>
```

2. The library uses a Handler to communicate to your UI for safety reasons, create a handler that listens for MQTT state and messages.
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
