package ru.ngscanner

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.ngscanner.obd.ObdPid

/** Табличная проверка формул декодирования всех PID (питают весь дашборд). */
class ObdPidDecodeTest {

    private fun decode(pid: ObdPid, vararg bytes: Int): Double = pid.decode(bytes)

    @Test fun rpm() = assertEquals(1726.0, decode(ObdPid.RPM, 0x1A, 0xF8), 0.01)
    @Test fun speed() = assertEquals(80.0, decode(ObdPid.SPEED, 0x50), 0.01)
    @Test fun coolant() = assertEquals(50.0, decode(ObdPid.COOLANT, 0x5A), 0.01)
    @Test fun engineLoad() = assertEquals(100.0, decode(ObdPid.ENGINE_LOAD, 0xFF), 0.01)
    @Test fun intakeTemp() = assertEquals(-6.0, decode(ObdPid.INTAKE_TEMP, 0x22), 0.01)
    @Test fun maf() = assertEquals(6.55, decode(ObdPid.MAF, 0x02, 0x8F), 0.01)
    @Test fun throttle() = assertEquals(50.196, decode(ObdPid.THROTTLE, 0x80), 0.01)
    @Test fun stft() = assertEquals(0.0, decode(ObdPid.STFT, 0x80), 0.01)
    @Test fun timing() = assertEquals(10.0, decode(ObdPid.TIMING, 0x94), 0.01)
    @Test fun map() = assertEquals(101.0, decode(ObdPid.MAP, 0x65), 0.01)
    @Test fun fuelLevel() = assertEquals(50.196, decode(ObdPid.FUEL_LEVEL, 0x80), 0.01)
    @Test fun voltage() = assertEquals(14.648, decode(ObdPid.VOLTAGE, 0x39, 0x38), 0.01)
}
