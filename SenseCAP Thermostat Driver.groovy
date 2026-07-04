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
 * SenseCAP Thermostat Driver v1.0.0
 *
 * Standalone Hubitat driver for a SenseCAP Indicator D1 (480x480) running
 * openHASP firmware, displaying a single thermostat in a 2x2 grid layout.
 *
 * Layout:
 *   [1] Temp + setpoint  [2] +
 *   [3] Mode             [4] -
 *
 * Communicates via MQTT. Works with the companion app
 * "openHASP Thermostat App".
 *
 * Prerequisites:
 *   - A String hub variable named "openHaspThermostatTap" (Settings → Hub Variables)
 *
 * Author: jlslate (slate)
 * Version: 1.1.0
 */

metadata {
    definition(
        name: "SenseCAP Thermostat",
        namespace: "jlslate",
        author: "jlslate (slate)",
        description: "Displays a thermostat on a SenseCAP Indicator D1 running openHASP"
    ) {
        capability "Initialize"
        capability "Actuator"

        command "reconnectMqtt"
        command "rebootDisplay"
        command "pushLayout"
        command "updateThermostatDisplay", [[name:"data", type:"JSON_OBJECT"]]

        attribute "mqttStatus",       "string"
        attribute "thermostatTapped", "string"
    }

    preferences {
        input name: "mqttBroker",   type: "text",   title: "MQTT Broker URL",       defaultValue: "tcp://127.0.0.1:1883", required: true
        input name: "mqttPassword", type: "password", title: "MQTT Password",        required: false
        input name: "mqttClientId", type: "text",   title: "MQTT Client ID (base)",  defaultValue: "hubitat-sensecap-thermostat", required: true
        input name: "haspNode",     type: "text",   title: "openHASP Node Name",     defaultValue: "plate", required: true

        input name: "colorHeating", type: "enum", title: "Color -- Heating",   options: colorOptions(), defaultValue: "#FF6600", required: true
        input name: "colorCooling", type: "enum", title: "Color -- Cooling",   options: colorOptions(), defaultValue: "#0088FF", required: true
        input name: "colorFan",     type: "enum", title: "Color -- Fan only",  options: colorOptions(), defaultValue: "#008080", required: true
        input name: "colorOff",     type: "enum", title: "Color -- Off",       options: colorOptions(), defaultValue: "#303030", required: true
        input name: "colorIdle",    type: "enum", title: "Color -- Idle",      options: colorOptions(), defaultValue: "#606060", required: true

        input name: "backlightTimeout", type: "number",
              title: "Backlight off after (seconds, 0 = never)", defaultValue: 0, required: true
        input name: "logLevel", type: "enum", title: "Logging Level",
              options: ["0":"None","1":"Info only","2":"Info + Debug"], defaultValue: "1", required: true
    }
}

// ── Logging ────────────────────────────────────────────────────────────────────

private void infoLog(String msg)  { if ((settings.logLevel as int) >= 1) log.info  msg }
private void debugLog(String msg) { if ((settings.logLevel as int) >= 2) log.debug msg }

// ── Color helpers ──────────────────────────────────────────────────────────────

private Map colorOptions() {
    ["#FF6600":"Orange","#0088FF":"Blue","#008080":"Teal","#303030":"Dark Gray",
     "#606060":"Gray","#FF0000":"Red","#00FF00":"Lime","#FFFF00":"Yellow",
     "#804020":"Dark Orange","#004488":"Dark Blue","#808080":"Medium Gray",
     "#000000":"Black","#FFFFFF":"White"]
}

private String contrastColor(String hex) {
    try {
        String h = hex.startsWith("#") ? hex.substring(1) : hex
        int r = Integer.parseInt(h.substring(0,2), 16)
        int g = Integer.parseInt(h.substring(2,4), 16)
        int b = Integer.parseInt(h.substring(4,6), 16)
        double lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        return (lum > 0.55) ? "#000000" : "#FFFFFF"
    } catch (Exception e) {
        return "#FFFFFF"
    }
}

// ── Icon JSON escape ───────────────────────────────────────────────────────────

private String iconToJsonEscape(String s) {
    if (!s) return ""
    if (s.length() == 1) {
        switch (s) {
            case "\uE415": return "\\uE415"   // plus
            case "\uE374": return "\\uE374"   // minus
        }
    }
    boolean allAscii = true
    for (int i = 0; i < s.length(); i++) {
        if ((int) s.charAt(i) > 127) { allAscii = false; break }
    }
    return allAscii ? s : ""
}

// ── Lifecycle ──────────────────────────────────────────────────────────────────

def installed()  { infoLog "[Thermostat] Driver installed"; initialize() }
def updated()    { infoLog "[Thermostat] Preferences updated"; initialize() }
def uninstalled(){ disconnectMqtt() }

def initialize() {
    String mqttSt = device.currentValue("mqttStatus") ?: ""
    if (!mqttSt.startsWith("Connected")) {
        connectMqtt()
    } else {
        infoLog "[Thermostat] MQTT already connected -- skipping reconnect"
    }
    unschedule("sendHeartbeat")
    runEvery1Minute("sendHeartbeat")
}

// ── MQTT ───────────────────────────────────────────────────────────────────────

private void safePub(String topic, String payload, int qos = 1, boolean retained = false) {
    try {
        interfaces.mqtt.publish(topic, payload, qos, retained)
    } catch (Exception e) {
        // Silent -- mqttClientStatus handles reconnect
    }
}

def connectMqtt() {
    if (!settings.mqttPassword) { infoLog "[Thermostat] MQTT password not set"; return }
    try {
        String broker   = settings.mqttBroker   ?: "tcp://127.0.0.1:1883"
        String clientId = (settings.mqttClientId ?: "hubitat-sensecap-thermostat") + "-" + device.id
        interfaces.mqtt.connect(broker, clientId, "hubitat", settings.mqttPassword)
        infoLog "[Thermostat] MQTT connected -> ${broker}"
        sendEvent(name: "mqttStatus", value: "Connected")
        String node = settings.haspNode ?: "plate"
        interfaces.mqtt.subscribe("hasp/${node}/state/statusupdate")
        interfaces.mqtt.subscribe("hasp/${node}/state/idle")
        interfaces.mqtt.subscribe("hasp/${node}/idle")
        interfaces.mqtt.subscribe("hasp/${node}/state/backlight")
        interfaces.mqtt.subscribe("hasp/+/LWT")
        interfaces.mqtt.subscribe("hasp/+/state/+")
        infoLog "[Thermostat] Subscribed -- node: ${node}"
    } catch (Exception e) {
        infoLog "[Thermostat] ERROR -- MQTT connect failed: ${e.message}"
        sendEvent(name: "mqttStatus", value: "Error: ${e.message}")
        runIn(30, "connectMqtt")
    }
}

def disconnectMqtt() {
    try { interfaces.mqtt.disconnect() } catch (Exception e) { }
    sendEvent(name: "mqttStatus", value: "Disconnected")
}

def reconnectMqtt() { disconnectMqtt(); pauseExecution(2000); connectMqtt() }

def rebootDisplay() {
    String node = settings.haspNode ?: "plate"
    infoLog "[Thermostat] Sending reboot command to display"
    safePub("hasp/${node}/command", "reboot")
}

def mqttClientStatus(String status) {
    infoLog "[Thermostat] MQTT status: ${status}"
    sendEvent(name: "mqttStatus", value: status)
    if (status.startsWith("Error") || status.contains("lost")) {
        infoLog "[Thermostat] MQTT lost -- reconnecting in 5s"
        runIn(5, "connectMqtt")
    }
}

def sendHeartbeat() {
    boolean connected = false
    try { connected = interfaces.mqtt.isConnected() } catch (Exception e) { connected = false }
    if (!connected) {
        infoLog "[Thermostat] Heartbeat: MQTT not connected -- reconnecting"
        connectMqtt()
        return
    }
    String node = settings.haspNode ?: "plate"
    try {
        interfaces.mqtt.publish("hasp/${node}/command", "statusupdate", 1, false)
    } catch (Exception e) {
        infoLog "[Thermostat] Heartbeat publish failed -- reconnecting"
        connectMqtt()
    }
}

// ── MQTT parse ─────────────────────────────────────────────────────────────────

def parse(String description) {
    def msg = interfaces.mqtt.parseMessage(description)
    debugLog "MQTT: topic=${msg.topic} payload=${msg.payload}"

    if (msg.topic.endsWith("/LWT")) {
        String actualNode = msg.topic.split("/")[1]
        String configNode = settings.haspNode ?: "plate"
        if (actualNode != configNode) return
        if (msg.payload?.trim() == "online") {
            infoLog "[Thermostat] Display rebooted -- pushing layout"
            state.pushInProgress = false
            runIn(5, "pushLayout")
        }
        return
    }

    if (msg.topic.contains("statusupdate")) {
        // Only process statusupdate from our configured node
        String updNode = msg.topic.split("/")[1]
        if (updNode != (settings.haspNode ?: "plate")) return
        if (!msg.payload?.trim()) return
        try {
            def json = new groovy.json.JsonSlurper().parseText(msg.payload)
            if (json.uptime == null) return
            int uptime = (json.uptime) as int
            if (uptime < 15) {
                // Only push if we haven't pushed in the last 30 seconds
                long lastPush = (state.lastPushMs ?: 0L) as long
                if (now() - lastPush > 30000) {
                    infoLog "[Thermostat] Display rebooted (uptime ${uptime}s) -- pushing layout"
                    state.pushInProgress = false
                    runIn(5, "pushLayout")
                }
            } else {
                infoLog "[Thermostat] Display woke from idle -- resyncing"
                runIn(2, "resyncDisplay")
            }
        } catch (Exception e) { }
        return
    }

    if (msg.topic.contains("state/idle") || msg.topic.endsWith("/idle")) {
        String v = msg.payload?.trim()
        if (v == "short" || v == "long") { state.screenOn = false }
        return
    }

    if (msg.topic.contains("state/backlight") || msg.topic.endsWith("/backlight")) {
        try {
            def json = new groovy.json.JsonSlurper().parseText(msg.payload)
            if (json.state == "off") { state.screenOn = false }
            else if (json.state == "on") { state.screenOn = true; scheduleBacklightOff() }
        } catch (Exception e) { }
        return
    }

    String cfgNode = settings.haspNode ?: "plate"
    if (msg.topic.contains("/state/p") && msg.topic.contains("b") && msg.topic.contains(cfgNode)) {
        debugLog "[Thermostat] Button topic: ${msg.topic} payload: ${msg.payload}"
        // If screen was off, first tap only wakes the backlight -- don't act on it
        if (!(state.screenOn)) {
            state.screenOn = true
            scheduleBacklightOff()
            String node = settings.haspNode ?: "plate"
            safePub("hasp/" + node + "/command/backlight", '{"state":"on","brightness":255}')
            scheduleBacklightOff()
            debugLog "[Thermostat] First tap -- waking display"
            return
        }
        handleButtonTap(msg.topic, msg.payload)
    }
}

// ── Button tap ─────────────────────────────────────────────────────────────────

private void handleButtonTap(String topic, String payload) {
    if (!payload?.contains('"up"')) return
    def matcher = topic =~ /state\/p(\d+)b(\d+)$/
    if (!matcher) return
    int btnId = matcher[0][2] as int
    if (btnId < 2 || btnId > 4) return
    infoLog "[Thermostat] Tile tapped: slot ${btnId} mode=${state.thermostatMode} heat=${state.thermostatHeat} cool=${state.thermostatCool}"

    String mode = (state.thermostatMode ?: "off") as String
    String heat = (state.thermostatHeat ?: "68") as String
    String cool = (state.thermostatCool ?: "76") as String
    String val  = "${btnId},${mode},${heat},${cool}"

    sendEvent(name: "thermostatTapped", value: val)
}

// ── Layout push ────────────────────────────────────────────────────────────────

def pushLayout() {
    if (state.pushInProgress) { infoLog "[Thermostat] pushLayout already in progress -- skipping"; return }
    boolean mqttUp = false
    try { mqttUp = interfaces.mqtt.isConnected() } catch (Exception e) { mqttUp = false }
    if (!mqttUp) {
        infoLog "[Thermostat] pushLayout deferred -- MQTT not connected"
        runIn(10, "pushLayout")
        return
    }
    state.pushInProgress = true
    state.lastPushMs = now()

    String node = settings.haspNode ?: "plate"
    infoLog "[Thermostat] Pushing thermostat layout"

    safePub("hasp/${node}/command/backlight", '{"state":"on","brightness":255}')
    safePub("hasp/${node}/command", "clearpage 1")
    pauseExecution(200)

    // 2x2 layout: 4 equal tiles on a 480x480 display
    // slot 1=info, 2=+, 3=mode, 4=-
    int[][] tiles = [[1,2,2,236,236],[2,242,2,236,236],[3,2,242,236,236],[4,242,242,236,236]]
    tiles.each { r ->
        int sid = r[0]; int x = r[1]; int y = r[2]; int w = r[3]; int h = r[4]
        int tf = (sid == 1) ? 32 : 56
        boolean clickable = (sid > 1)
        safePub("hasp/${node}/command/jsonl",
            """{"page":1,"id":${sid},"obj":"btn","x":${x},"y":${y},"w":${w},"h":${h},"bg_color":"#000000","border_color":"black","border_width":4,"radius":10,"text":"","text_font":${tf},"align":"center","text_color":"white","toggle":false,"click":${clickable}}""")
        pauseExecution(40)
    }
    pauseExecution(200)

    // Set slot types in state
    state["p1slotType1"] = "thermostat"
    state["p1slotType2"] = "thermostat"
    state["p1slotType3"] = "thermostat"
    state["p1slotType4"] = "thermostat"

    // Navigate to page 1
    safePub("hasp/${node}/command/page", "1")
    state.screenOn = true
    scheduleBacklightOff()

    // Push thermostat data after display has rendered layout
    runIn(3, "resyncDisplay")
    runIn(5, "clearPushInProgress")
}

def clearPushInProgress() {
    state.pushInProgress = false
}

// ── Thermostat display update ──────────────────────────────────────────────────

// Called by the app when thermostat attributes change.
// data map keys: temp, heatSetpoint, coolSetpoint, mode, operatingState
def updateThermostatDisplay(data) {
    if (!data) return
    String temp    = data.temp          ?: "--"
    String heat    = data.heatSetpoint  ?: "--"
    String cool    = data.coolSetpoint  ?: "--"
    String mode    = data.mode          ?: "off"
    String opState = data.operatingState ?: "idle"
    boolean away   = (data.away == "true")

    // Store in state for resync
    state.thermostatTemp    = temp
    state.thermostatHeat    = heat
    state.thermostatCool    = cool
    state.thermostatMode    = mode
    state.thermostatOpState = opState
    state.thermostatAway    = away

    pushThermostatTile(settings.haspNode ?: "plate", temp, heat, cool, mode, opState, away)
}

def backlightOff() {
    String node = settings.haspNode ?: "plate"
    safePub("hasp/${node}/command/backlight", '{"state":"off"}')
    state.screenOn = false
    infoLog "[Thermostat] Backlight off"
}

private void scheduleBacklightOff() {
    int secs = (settings.backlightTimeout != null ? settings.backlightTimeout : 0) as int
    unschedule("backlightOff")
    if (secs > 0) runIn(secs, "backlightOff")
}

def resyncDisplay() {
    String temp    = (state.thermostatTemp    ?: "--") as String
    String heat    = (state.thermostatHeat    ?: "--") as String
    String cool    = (state.thermostatCool    ?: "--") as String
    String mode    = (state.thermostatMode    ?: "off") as String
    String opState = (state.thermostatOpState ?: "idle") as String
    if (temp == "--") {
        infoLog "[Thermostat] No thermostat data yet -- waiting for app sync"
        return
    }
    boolean away = (state.thermostatAway == true)
    pushThermostatTile(settings.haspNode ?: "plate", temp, heat, cool, mode, opState, away)
}

private void pushThermostatTile(String node, String temp, String heat, String cool, String mode, String opState, boolean away = false) {
    // Tile 1: current temp + descriptive second line
    // Line 2 reflects actual operating state, not just mode
    String line2
    // Determine if actively heating/cooling based on mode, opState, and temp vs setpoint
    boolean activeHeat = (opState == "heating") ||
                         (mode == "heat" && (!opState || opState == "idle" || opState == ""))
    boolean activeCool = (opState == "cooling") ||
                         (mode == "cool" && (!opState || opState == "idle" || opState == ""))
    // If temp has already passed setpoint, show idle state regardless
    try {
        BigDecimal t = temp as BigDecimal
        BigDecimal h = heat as BigDecimal
        BigDecimal c = cool as BigDecimal
        if (activeHeat && t >= h) activeHeat = false
        if (activeCool && t <= c) activeCool = false
    } catch (Exception e) { }

    if (activeHeat) {
        line2 = "Heating to " + heat + "\u00B0"
    } else if (activeCool) {
        line2 = "Cooling to " + cool + "\u00B0"
    } else if (opState == "fan only") {
        line2 = "Fan only"
    } else if (mode == "heat") {
        line2 = "Heat: " + heat + "\u00B0"
    } else if (mode == "cool") {
        line2 = "Cool: " + cool + "\u00B0"
    } else {
        line2 = "Off"
    }
    String mainText = temp + "\u00B0\n" + line2
    String bgColor  = thermostatColorFor(opState, mode)
    String fgColor  = contrastColor(bgColor)
    debugLog "[Thermostat] Tile1: temp=${temp} mode=${mode} line2=${line2} bg=${bgColor}"
    String mainJsonl = '{"page":1,"id":1,"bg_color":"' + bgColor + '","text_color":"' + fgColor + '","text":"' + mainText + '"}'
    safePub("hasp/" + node + "/command/jsonl", mainJsonl)
    pauseExecution(20)

    // Tiles 2(+) and 4(-): active only when home AND in heat/cool mode
    boolean active = !away && (mode == "heat" || mode == "cool")
    String btnBg = active ? "#1A2A3A" : "#222222"
    String btnFg = active ? "#FFFFFF" : "#555555"

    String plusGlyph  = iconToJsonEscape("\uE415")
    String minusGlyph = iconToJsonEscape("\uE374")

    String plusJsonl  = '{"page":1,"id":2,"bg_color":"' + btnBg + '","text_color":"' + btnFg + '","text":"' + plusGlyph  + '","click":' + active + '}'
    safePub("hasp/" + node + "/command/jsonl", plusJsonl)
    pauseExecution(15)

    // Tile 3: mode button -- shows "Away" when in away mode
    String modeLabel = away ? "Away" : mode.capitalize()
    String modeBg    = away ? "#440000" : "#1A2A3A"
    String modeJsonl = '{"page":1,"id":3,"bg_color":"' + modeBg + '","text_color":"#FFFFFF","text":"' + modeLabel + '","click":true}'
    safePub("hasp/" + node + "/command/jsonl", modeJsonl)
    pauseExecution(15)

    String minusJsonl = '{"page":1,"id":4,"bg_color":"' + btnBg + '","text_color":"' + btnFg + '","text":"' + minusGlyph + '","click":' + active + '}'
    safePub("hasp/" + node + "/command/jsonl", minusJsonl)
}

private String thermostatColorFor(String opState, String mode) {
    switch (opState) {
        case "heating":  return (settings.colorHeating ?: "#FF6600")
        case "cooling":  return (settings.colorCooling ?: "#0088FF")
        case "fan only": return (settings.colorFan     ?: "#008080")
        case "off":      return (settings.colorOff     ?: "#303030")
        default:
            if      (mode == "heat") return "#804020"
            else if (mode == "cool") return "#004488"
            else if (mode == "off")  return (settings.colorOff  ?: "#303030")
            return (settings.colorIdle ?: "#606060")
    }
}
