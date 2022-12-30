/* Remotec ZFM-80 specific device V1.2
 *
 * Variation of the stock SmartThings Relay Switch
 *  --auto re-configure after setting preferences
 *  --preference settings for switch type and automatic shutoff features.
 *
 * Author:
 * Chad Dreveny
 *
 * Original Author:
 * Mike Maxwell
 * madmax98087@yahoo.com
 * 2015-02-16
 *
 * change log
 *   2015-02-16 added delay between configuration changes, helps with devices further away from the hub.
 *   2015-02-21 fixed null error on initial install
 *   2018-05-22 Forked to clean up and add new options and added momentary switch option.
 *   2022-09-01 Modified to work with Hubitat
 *   2022-12-25 Minor clean up and make it look more like a Hubitat driver's logging.
*/

metadata {

  definition (name: "Remotec ZFM-80", namespace: "dreveny", author: "Chad Dreveny") {
    capability "Actuator"
    capability "Initialize"
    capability "Momentary"
    capability "Polling"
    capability "Refresh"
    capability "Relay Switch"
    capability "Sensor"
    capability "Switch"
 
    fingerprint deviceId: "0x1003", inClusters: "0x20, 0x25, 0x27, 0x72, 0x86, 0x70, 0x85"
  }
  preferences {
    input name: "extSwitchType", type: "enum", title: "Set external switch mode:",
      description: "Switch type", defaultValue: "Toggle NO", required: true,
      options: ["Disabled", "Momentary NO", "Momentary NC", "Toggle NO", "Toggle NC"]
    input name: "autoShutoffMinutes", type: "enum", title: "Auto shutoff minutes:",
      description: "Minutes?", required: false,
      options: ["Never", "1", "5", "30", "60", "90", "120", "240"]
    input name: "momentary", type: "number", title: "Momentary relay seconds (0 to disable):",
      description: "Seconds after which relay will be closed"
      required: false
    input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: false
  }
}

/****************************
 * Set up
****************************/
void installed() {
	logDebug "installed..."
	initialize()
}

void initialize() {
	logDebug "initialize"
	refresh()
}

def configure() {
	logDebug "configure"
  // Configure the device to the defaults (Toggle Normally Open switch, no auto-shutoff).
  delayBetween([
      zwave.configurationV1.configurationSet(parameterNumber: 1, size: 1, configurationValue: [3]).format(),
      zwave.configurationV1.configurationSet(parameterNumber: 2, size: 1, configurationValue: [0]).format(),
      zwave.configurationV1.configurationSet(parameterNumber: 3, size: 1, configurationValue: [0]).format(),
      zwave.configurationV1.configurationSet(parameterNumber: 4, size: 1, configurationValue: [0]).format()
  ])
}

/****************************
 * Core functionality
****************************/

def parse(String description) {
  logDebug "parse(): $description"
  def result = null
  def cmd = zwave.parse(description, [0x20: 1, 0x70: 1])
  if (cmd) {
    result = createEvent(zwaveEvent(cmd))
    if (result != null && !result.empty) logInfo(result.toString())
  }
  return result
}

def zwaveEvent(hubitat.zwave.commands.basicv1.BasicReport cmd) {
  logDebug "BasicReport: $cmd"
  [name: "switch", value: cmd.value ? "on" : "off", type: "physical"]
}

def zwaveEvent(hubitat.zwave.commands.switchbinaryv1.SwitchBinaryReport cmd) {
  logDebug "SwitchBinaryReport: $cmd"
  [name: "switch", value: cmd.value ? "on" : "off", type: "digital"]
}

def zwaveEvent(hubitat.zwave.commands.hailv1.Hail cmd) {
  logDebug "Hail: $cmd"
  [name: "hail", value: "hail", descriptionText: "Switch button was pressed", displayed: false]
}

def zwaveEvent(hubitat.zwave.commands.manufacturerspecificv2.ManufacturerSpecificReport cmd) {
  if (state.manufacturer != cmd.manufacturerName) {
    updateDataValue("manufacturer", cmd.manufacturerName)
  }

  final relays = [
    [manufacturerId:0x5254, productTypeId: 0x8000, productId: 0x0002, productName: "Remotec ZFM-80"]
  ]

  def productName  = null
  for (it in relays) {
    if (it.manufacturerId == cmd.manufacturerId &&
        it.productTypeId == cmd.productTypeId &&
        it.productId == cmd.productId) {
      productName = it.productName
      break
    }
  }

  if (productName) {
    logDebug "Relay found: $productName"
    updateDataValue("productName", productName)
  }
  else {
    logDebug "Not a relay, retyping to Z-Wave Switch"
    setDeviceType("Z-Wave Switch")
  }
  [name: "manufacturer", value: cmd.manufacturerName]
}

def zwaveEvent(hubitat.zwave.Command cmd) {
  // Handles all Z-Wave commands we aren't interested in
  [:]
}

def push() {
  delayBetween([
      zwave.basicV1.basicSet(value: 0xFF).format(),
      zwave.switchBinaryV1.switchBinaryGet().format(),
      "delay ${(settings.momentary ?: 3) * 1000}",
      zwave.basicV1.basicSet(value: 0x00).format(),
      zwave.switchBinaryV1.switchBinaryGet().format()
  ])
}

def on() {
  if ((settings.momentary ?: 0) != 0) {
    push()
  } else {
    delayBetween([
        zwave.basicV1.basicSet(value: 0xFF).format(),
        zwave.switchBinaryV1.switchBinaryGet().format()
    ])
  }
}

def off() {
  delayBetween([
      zwave.basicV1.basicSet(value: 0x00).format(),
      zwave.switchBinaryV1.switchBinaryGet().format()
  ])
}

def poll() {
  logDebug "Poll"
  zwave.switchBinaryV1.switchBinaryGet().format()
}

def refresh() {
  delayBetween([
      zwave.switchBinaryV1.switchBinaryGet().format(),
      // Causes a ManufacturerSpecificReport to be issued.
      zwave.manufacturerSpecificV1.manufacturerSpecificGet().format()
  ])
}

// Capture preference changes
def updated() {
    logDebug "current settings: ${settings.inspect()}, state: ${state.inspect()}"

    // External switch function settings
    def Short p1 = 0
    switch (settings.extSwitchType) {
    case "Disabled":
      p1 = 0
      break
    case "Momentary NO":
      p1 = 1
      break
    case "Momentary NC":
      p1 = 2
      break
    case "Toggle NO":
      p1 = 3
      break
    case "Toggle NC":
      p1 = 4
      break
  }
    
  // Auto-off
  def Short p2 = 0
  if ("${settings.autoShutoffMinutes}" == "Never") {
    p2 = 0
  } else {
    p2 = (settings.autoShutoffMinutes ?: 0).toInteger()
  }
  
  def configResponses = []
  
  if (p1 != state.extSwitchType) {
    state.extSwitchType = p1
    configResponses.add(
      zwave.configurationV1.configurationSet(
        parameterNumber: 1, size: 1, configurationValue: [p1]).format())
  }
    
  if (p2 != state.autoShutoffMinutes) {
    state.autoShutoffMinutes = p2
    if (p2 == 0) {
      configResponses.addAll([
        zwave.configurationV1.configurationSet(parameterNumber: 2, size: 1, configurationValue: [0]).format(),
        zwave.configurationV1.configurationSet(parameterNumber: 3, size: 1, configurationValue: [0]).format(),
        zwave.configurationV1.configurationSet(parameterNumber: 4, size: 1, configurationValue: [0]).format()])
    } else {
      configResponses.addAll([
        zwave.configurationV1.configurationSet(parameterNumber: 2, size: 1, configurationValue: [p2]).format(),
        zwave.configurationV1.configurationSet(parameterNumber: 3, size: 1, configurationValue: [232]).format(),
        zwave.configurationV1.configurationSet(parameterNumber: 4, size: 1, configurationValue: [0]).format()])
    }
  }
  
  if (!configResponses.empty) {
    logDebug "Updating zwave configuration: $configResponses"
    response(delayBetween(configResponses))
  }
}

/****************************
 * Logging Functions
****************************/
void logInfo(String msg) {
  if (txtEnable) log.info "${device.displayName}: ${msg}"
}

void logDebug(String msg) {
  if (debugEnable) log.debug "${device.displayName}: ${msg}"
}

