package com.adamate.aaforaaos.aap.protocol.messages

import com.adamate.aaforaaos.aap.AapMessage
import com.adamate.aaforaaos.aap.protocol.Channel
import com.adamate.aaforaaos.aap.protocol.proto.Sensors
import com.google.protobuf.Message

open class SensorEvent(val sensorType: Int, proto: Message)
    : AapMessage(Channel.ID_SEN, Sensors.SensorsMsgType.SENSOR_EVENT_VALUE, proto)
