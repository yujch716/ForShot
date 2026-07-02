package com.photo.thirds

import dji.sdk.keyvalue.value.common.LocationCoordinate3D

typealias FrameCallback = (nv21: ByteArray, width: Int, height: Int) -> Unit

interface FrameSource {
    fun setFrameCallback(cb: FrameCallback)
    fun getGpsLocation(): LocationCoordinate3D? = null
    fun release()
}
