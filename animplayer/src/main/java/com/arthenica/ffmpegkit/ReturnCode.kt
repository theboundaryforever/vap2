package com.arthenica.ffmpegkit

class ReturnCode(private val value: Int) {

    fun isValueSuccess(): Boolean = value == SUCCESS

    fun value(): Int = value

    override fun toString(): String = value.toString()

    companion object {
        const val SUCCESS = 0
        const val CANCEL = 255
    }
}
