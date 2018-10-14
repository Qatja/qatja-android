## Qatja Android library [![Build Status](https://travis-ci.org/Qatja/qatja-android.svg?branch=master)](https://travis-ci.org/Qatja/qatja-android)

This library creates a new service called QatjaService.java which allows for a easy connection to any MQTT server running standard version 3.1.1

### Installation

[![Download](https://api.bintray.com/packages/wetcat/Qatja/qatja-android/images/download.svg)](https://bintray.com/wetcat/Qatja/qatja-android/_latestVersion)

### Enabling MQTT in your project

First add the MQTT service to your manifest

```xml
<service
    android:name="se.wetcat.qatja.android.QatjaService"
    android:exported="false"/>
```

Declare the needed variabled

```kotlin
lateinit var mClient: QatjaService

private var isBound = false
private var isBinding = false

private val mHandler: Handler = Handler(MqttCallback())
```

The library uses a Handler to communicate to your UI for safety reasons, create a handler that listens for MQTT state and messages.

```kotlin
private inner class MqttCallback : Handler.Callback {
    override fun handleMessage(msg: Message?): Boolean {
        msg?.let {
            when (it.what) {
                MQTTConnectionConstants.STATE_CHANGE -> {
                    when (msg.arg1) {
                        MQTTConnectionConstants.STATE_NONE -> {
                            onMqttDisconnected()
                        }
                        MQTTConnectionConstants.STATE_CONNECTING -> {
                        }
                        MQTTConnectionConstants.STATE_CONNECTED -> {
                            onMqttConnected()
                        }
                        MQTTConnectionConstants.STATE_CONNECTION_FAILED -> {
                            onMqttDisconnected()
                        }
                        else -> {
                            Log.e(TAG, "Unhandled MQTT state change")
                        }
                    }
                }
                3 -> { //MQTTConstants.PUBLISH (has value 3!)
                    val publish: MQTTPublish = msg.obj as MQTTPublish
                }
            }
        }

        return true
    }
}
```

Create a service connection instance. Also, set your previously created handler as the service handler

```kotlin
private val mConnection: ServiceConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        mClient = (binder as QatjaService.QatjaBinder).service as QatjaService

        isBound = true
        isBinding = false

        mClient.setHandler(mHandler)
    }

    override fun onServiceDisconnected(p0: ComponentName?) {
        isBound = false
        isBinding = false
    }
}
```

Bind to the service when application starts.

```kotlin
override fun onResume() {
    super.onResume()

    if (!isBound && !isBinding) {
        isBinding = true

        Intent(this@MqttActivity, QatjaService::class.java).apply {
            bindService(this, mConnection, Context.BIND_AUTO_CREATE)
        }
    }
}
```

Unbind the service when application is stopped.

```kotlin
override fun onPause() {
    try {
        mClient.disconnect()

        unbindService(mConnection)

        isBinding = false
        isBound = false
    } catch (ex: IllegalArgumentException) {
        Log.e(TAG, "Couldn't unbind the service, this is probably fine considering the state changes in the app... can probably ignore this.", ex)
    }

    super.onPause()
}
```

## Example usage

The following small examples show how basic usage for MQTT.

## Connect to MQTT Server

Connecting to an MQTT server can only be done once the service connection has been established. Therefore you're recommended to attempt the connection in the service connection object after the Handler has been linked.
You can of course issue the connection elsewhere, as long as you make sure the service connection is "alive and kicking".

```kotlin
// Default host is test.mosquitto.org (you should change this!)
mClient.setHost(host)

// Default mqtt port is 1883
mClient.setPort(1883)

// Set a unique id for this client-broker combination
mClient.setId(clientIdentifier)

// Set keep alive time
mClient.setKeepAlive(3000)

// Set username and password
mClient.setUsername("my-username")
mClient.setPassword("my-password")

// Open the connection to the MQTT server
mClient.connect()
```

## Subscribe to topic

There are multiple ways of subscribing to a topic.

```kotlin
// Subscribe to a topic with Quality of Service AT_MOST_ONCE
mClient.subscribe(topic)

// Subscribe to a topic with specified Quality of Service ()
mClient.subscribe(topic, EXACTLY_ONCE)

// Subscribe to multiple topics (String[]) with Quality of Service AT_MOST_ONCE
mClient.subscribe(topics[])

// Subscribe to multiple topics (String[]) with specified quality of service (byte[]) for each topic
mClient.subscribe(topics[], qoss[])
```

## Publish to topic

There are multiple ways of publishing to an MQTT topic.

```kotlin
// Publish a String message
mClient.publish("mytopic", "my message")

// Publish a byte[] message
mClient.publish("mytopic", message[])

// Publish a byte[] message with RETAIN flag set
mClient.publish("mytopic", message[], true)
```
