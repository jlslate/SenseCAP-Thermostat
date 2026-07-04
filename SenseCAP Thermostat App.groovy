/*
 * This is free and unencumbered software released into the public domain.
 *
 * Anyone is free to copy, modify, publish, use, compile, sell, or distribute
 * this software, either in source code form or as a compiled binary, for any
 * purpose, commercial or non-commercial, and by any means.
 *
 * In jurisdictions that recognize copyright laws, the author or authors of
 * this software dedicate any and all copyright interest in the software to
 * the public domain. We make this dedication for the benefit of the public
 * at large and to the detriment of our heirs and successors. We intend this
 * dedication to be an overt act of relinquishment in perpetuity of all
 * present and future rights to this software under copyright law.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
 * OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 * For more information, please refer to <https://unlicense.org>
 */

/*
 * SenseCAP Thermostat App v1.0.0
 *
 * Companion app for the "openHASP Thermostat" driver.
 * Subscribes to thermostat device attribute changes and routes them to the driver.
 * Handles button taps via hub variable.
 *
 * Prerequisites:
 *   - "openHASP Thermostat" driver installed and device created
 *
 * Author: jlslate (slate)
 * Version: 1.1.0
 */

definition(
    name:        "SenseCAP Thermostat App",
    namespace:   "jlslate",
    author:      "jlslate (slate)",
    description: "Links a thermostat device to a SenseCAP Indicator D1 display via the SenseCAP Thermostat driver",
    category:    "Convenience",
    iconUrl:     "",
    iconX2Url:   ""
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "SenseCAP Thermostat", install: true, uninstall: true) {
        section {
            label title: "App name", required: false
        }
        section("Display Device") {
            input name: "displayDevice",
                  type: "device.SenseCAPThermostat",
                  title: "openHASP Thermostat device",
                  required: true,
                  multiple: false
        }
        section("Thermostat") {
            input name: "thermostatDevice",
                  type: "capability.thermostat",
                  title: "Thermostat to display",
                  required: true,
                  multiple: false
            input name: "tempSensor",
                  type: "capability.temperatureMeasurement",
                  title: "Temperature sensor (optional -- uses thermostat temp if blank)",
                  required: false,
                  multiple: false
        }
        section("Temperature Limits") {
            paragraph "Hub variable names for setpoint limits (Settings → Hub Variables)"
            input name: "varAwayHigh", type: "text", title: "Away High variable name",  defaultValue: "main bedroom away high", required: true
            input name: "varAwayLow",  type: "text", title: "Away Low variable name",   defaultValue: "main bedroom away low", required: true
            input name: "varHereHigh", type: "text", title: "Home High variable name",  defaultValue: "main bedroom here high", required: true
            input name: "varHereLow",  type: "text", title: "Home Low variable name",   defaultValue: "main bedroom here low", required: true
        }
        section("Options") {
            input name: "logLevel", type: "enum", title: "Logging Level",
                  options: ["0":"None","1":"Info only","2":"Info + Debug"],
                  defaultValue: "1", required: true
        }
    }
}

// ── Logging ────────────────────────────────────────────────────────────────────

private void infoLog(String msg)  { if ((settings.logLevel as int) >= 1) log.info  msg }
private void debugLog(String msg) { if ((settings.logLevel as int) >= 2) log.debug msg }

// ── Lifecycle ──────────────────────────────────────────────────────────────────

def installed() {
    infoLog "[ThermostatApp] Installed"
    initialize()
}

def updated() {
    infoLog "[ThermostatApp] Updated"
    initialize()
    // Reboot display on save so layout is always fresh
    runIn(3, "rebootDisplay")
}

def rebootDisplay() {
    try {
        settings.displayDevice?.rebootDisplay()
        infoLog "[ThermostatApp] Display reboot triggered"
    } catch (Exception e) {
        infoLog "[ThermostatApp] WARN -- rebootDisplay failed: ${e.message}"
    }
}

def uninstalled() {
    unsubscribe()
    unschedule()
}

def initialize() {
    unsubscribe()
    unschedule()

    if (!settings.thermostatDevice || !settings.displayDevice) {
        infoLog "[ThermostatApp] Waiting for device configuration"
        return
    }

    // Subscribe to thermostat attribute changes
    subscribe(settings.thermostatDevice, "temperature",              thermostatHandler)
    subscribe(settings.thermostatDevice, "heatingSetpoint",          thermostatHandler)
    subscribe(settings.thermostatDevice, "coolingSetpoint",          thermostatHandler)
    subscribe(settings.thermostatDevice, "thermostatMode",           thermostatHandler)
    subscribe(settings.thermostatDevice, "thermostatOperatingState", thermostatHandler)

    subscribe(settings.displayDevice, "thermostatTapped", tapHandler)

    // Subscribe to separate temp sensor if configured
    if (settings.tempSensor) {
        subscribe(settings.tempSensor, "temperature", tempSensorHandler)
    }

    // Subscribe to location mode changes
    subscribe(location, "mode", modeHandler)

    // Periodic sync every minute -- fallback for unreliable attribute subscriptions
    runEvery1Minute("syncDisplay")

    infoLog "[ThermostatApp] Initialized -- subscribed to ${settings.thermostatDevice.displayName}"

    // Push current state to display
    runIn(2, "syncDisplay")
    // Run auto-control in case mode is already Away
    runIn(5, "runAutoControl")
}

// ── Thermostat attribute change handler ────────────────────────────────────────

def thermostatHandler(evt) {
    debugLog "[ThermostatApp] ${evt.device.displayName} ${evt.name}=${evt.value}"
    runIn(1, "syncDisplay")
    // Re-run auto-control on any thermostat change (especially temperature)
    if (isAway()) runIn(2, "runAutoControl")
}

// ── Mode and limit helpers ─────────────────────────────────────────────────────

private boolean isAway() {
    return location.mode == "Away"
}

private BigDecimal getLimit(String varName) {
    try {
        def v = getGlobalVar(varName)
        if (v?.value != null) return v.value as BigDecimal
    } catch (Exception e) { }
    return null
}

def tempSensorHandler(evt) {
    debugLog "[ThermostatApp] Temp sensor: ${evt.value}"
    runIn(1, "syncDisplay")
    if (isAway()) runIn(2, "runAutoControl")
}

private BigDecimal getCurrentTemp() {
    if (settings.tempSensor) {
        def v = settings.tempSensor.currentValue("temperature")
        debugLog "[ThermostatApp] Temp sensor '${settings.tempSensor.displayName}' = ${v}"
        if (v != null) return v as BigDecimal
    }
    infoLog "[ThermostatApp] WARNING: no temperature sensor selected or no value"
    return null
}

def modeHandler(evt) {
    infoLog "[ThermostatApp] Mode changed to: ${evt.value}"
    // Re-sync display (away state affects button appearance)
    runIn(1, "syncDisplay")
    // Run auto-control immediately on mode change
    runIn(2, "runAutoControl")
}

// Auto-control: when Away, command thermostat to stay between mbal and mbah.
// When Home, do nothing (user controls manually within bounds).
def runAutoControl() {
    if (!settings.thermostatDevice) return
    def dev = settings.thermostatDevice
    boolean away = isAway()
    infoLog "[ThermostatApp] runAutoControl: away=${away}"

    if (!away) return  // Home mode -- don't auto-command

    BigDecimal awayHigh = getLimit(settings.varAwayHigh ?: "main bedroom away high")
    BigDecimal awayLow  = getLimit(settings.varAwayLow  ?: "main bedroom away low")
    if (awayHigh == null || awayLow == null) {
        infoLog "[ThermostatApp] Auto-control: hub variables not set (${settings.varAwayHigh}, ${settings.varAwayLow})"
        return
    }

    BigDecimal temp = getCurrentTemp()
    if (temp == null) { infoLog "[ThermostatApp] Auto-control: no temperature reading"; return }

    String currentMode = dev.currentValue("thermostatMode") ?: "off"
    String targetMode

    if (temp > awayHigh) {
        targetMode = "cool"
        infoLog "[ThermostatApp] Away: temp ${temp} > high ${awayHigh} -- cooling"
    } else if (temp < awayLow) {
        targetMode = "heat"
        infoLog "[ThermostatApp] Away: temp ${temp} < low ${awayLow} -- heating"
    } else {
        targetMode = "off"
        infoLog "[ThermostatApp] Away: temp ${temp} within [${awayLow}-${awayHigh}] -- off"
    }

    if (currentMode != targetMode) {
        infoLog "[ThermostatApp] Away: setting mode ${currentMode} -> ${targetMode}"
        dev.setThermostatMode(targetMode)
    }
}

def syncDisplay() {
    if (!settings.thermostatDevice || !settings.displayDevice) return
    def dev = settings.thermostatDevice
    String heat  = (dev.currentValue("heatingSetpoint")          ?: "--").toString()
    String cool  = (dev.currentValue("coolingSetpoint")          ?: "--").toString()
    String mode  = (dev.currentValue("thermostatMode")           ?: "off").toString()
    String opSt  = (dev.currentValue("thermostatOperatingState") ?: "idle").toString()
    BigDecimal sensorTemp = getCurrentTemp()
    String displayTemp = sensorTemp != null ? sensorTemp.toString() : "--"
    infoLog "[ThermostatApp] Sync: temp=${displayTemp} heat=${heat} cool=${cool} mode=${mode} op=${opSt}"

    boolean away = isAway()
    Map data = [temp: displayTemp, heatSetpoint: heat, coolSetpoint: cool, mode: mode, operatingState: opSt, away: away.toString()]
    try {
        settings.displayDevice.updateThermostatDisplay(data)
    } catch (Exception e) {
        infoLog "[ThermostatApp] WARN -- updateThermostatDisplay failed: ${e.message}"
    }
}

// ── Button tap handler (via hub variable) ──────────────────────────────────────

def tapHandler(evt) {
    // evt.value = "slot,mode,heat,cool"
    infoLog "[ThermostatApp] Tap event: ${evt.value}"
    List parts = evt.value?.split(",")
    if (!parts || parts.size() < 4) { infoLog "[ThermostatApp] Bad tap value: ${evt.value}"; return }

    int    slot = parts[0] as int
    String mode = parts[1]
    BigDecimal heat = parts[2] as BigDecimal
    BigDecimal cool = parts[3] as BigDecimal

    def dev = settings.thermostatDevice
    if (!dev) { infoLog "[ThermostatApp] No thermostat device configured"; return }

    // Mode cycle: cool -> off -> heat -> cool
    List<String> cycle = ["cool", "off", "heat"]

    infoLog "[ThermostatApp] Tap slot ${slot}: mode=${mode} heat=${heat} cool=${cool} away=${isAway()}"

    // +/- disabled when away
    if (isAway() && (slot == 2 || slot == 4)) {
        infoLog "[ThermostatApp] Away mode -- +/- taps ignored"
        return
    }

    // Home limits
    BigDecimal hereHigh = getLimit(settings.varHereHigh ?: "main bedroom here high")
    BigDecimal hereLow  = getLimit(settings.varHereLow  ?: "main bedroom here low")

    switch (slot) {
        case 2:   // +
            if (mode == "heat") {
                BigDecimal newSp = heat + 1
                if (hereHigh != null) newSp = Math.min(newSp, hereHigh)
                infoLog "[ThermostatApp] Heat setpoint: ${heat} -> ${newSp}"
                dev.setHeatingSetpoint(newSp)
            } else if (mode == "cool") {
                BigDecimal newSp = cool + 1
                if (hereHigh != null) newSp = Math.min(newSp, hereHigh)
                infoLog "[ThermostatApp] Cool setpoint: ${cool} -> ${newSp}"
                dev.setCoolingSetpoint(newSp)
            }
            break
        case 3:   // Mode cycle
            String liveMode = dev.currentValue("thermostatMode") ?: "off"
            int curIdx = cycle.indexOf(liveMode)
            if (curIdx < 0) curIdx = 0
            String next = cycle[(curIdx + 1) % cycle.size()]
            infoLog "[ThermostatApp] Mode: ${liveMode} -> ${next}"
            dev.setThermostatMode(next)
            break
        case 4:   // -
            if (mode == "heat") {
                BigDecimal newSp = heat - 1
                if (hereLow != null) newSp = Math.max(newSp, hereLow)
                infoLog "[ThermostatApp] Heat setpoint: ${heat} -> ${newSp}"
                dev.setHeatingSetpoint(newSp)
            } else if (mode == "cool") {
                BigDecimal newSp = cool - 1
                if (hereLow != null) newSp = Math.max(newSp, hereLow)
                infoLog "[ThermostatApp] Cool setpoint: ${cool} -> ${newSp}"
                dev.setCoolingSetpoint(newSp)
            }
            break
    }

    // Sync display 3s after command so thermostat has time to update
    runIn(3, "syncDisplay")
}
