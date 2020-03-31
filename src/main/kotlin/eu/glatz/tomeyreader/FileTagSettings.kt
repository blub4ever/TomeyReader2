package eu.glatz.tomeyreader

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "tomey")
class FileTagSettings {

    lateinit var width: String
    lateinit var height: String
    lateinit var depth: String

    var patient: Patient = Patient()


    class Patient {
        lateinit var id: String
        lateinit var firstName: String
        lateinit var lastName: String
        lateinit var birthday: String
        lateinit var eye: String
        lateinit var commentary: String
    }

    final val nullChar = '\u0000'
    final val dleChar = 16.toChar()
    final val soChar = 14.toChar()
    final val newLineChar = 10.toChar()
    final val returnChar = 13.toChar()
    final val lineBreak = String(charArrayOf(returnChar, newLineChar))
}