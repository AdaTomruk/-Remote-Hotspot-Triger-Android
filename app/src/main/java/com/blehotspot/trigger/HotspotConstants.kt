package com.blehotspot.trigger

/**
 * Constants for hotspot control integration with Samsung Routines.
 * 
 * To set up Samsung Routines integration:
 * 1. Open Samsung Routines app on your Samsung device
 * 2. Create a new routine with "If: Receive specific broadcast"
 * 3. Set the action name to match ACTION_ENABLE_HOTSPOT or ACTION_DISABLE_HOTSPOT
 * 4. Add "Then: Turn on/off Mobile hotspot" action
 * 5. Save and enable the routine
 */
object HotspotConstants {
    // Intent actions for Samsung Routines integration
    const val ACTION_ENABLE_HOTSPOT = "com.blehotspot.trigger.ENABLE_HOTSPOT"
    const val ACTION_DISABLE_HOTSPOT = "com.blehotspot.trigger.DISABLE_HOTSPOT"
    
    // Extra key for hotspot state
    const val EXTRA_HOTSPOT_STATE = "hotspot_state"
    
    // Samsung Routines package name
    const val SAMSUNG_ROUTINES_PACKAGE = "com.samsung.android.app.routines"
    
    // Notification constants for Samsung Routines trigger
    const val ROUTINE_TRIGGER_CHANNEL_ID = "routine_trigger_channel"
    const val ROUTINE_TRIGGER_CHANNEL_NAME = "Automation Triggers"
    const val NOTIFICATION_ID_ENABLE = 1001
    const val NOTIFICATION_ID_DISABLE = 1002
    const val NOTIFICATION_TIMEOUT_MS = 1000L
    
    // Notification trigger keywords
    const val TRIGGER_ENABLE_HOTSPOT = "ENABLE_HOTSPOT_TRIGGER"
    const val TRIGGER_DISABLE_HOTSPOT = "DISABLE_HOTSPOT_TRIGGER"
}
