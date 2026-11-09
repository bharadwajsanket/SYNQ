package dev.synq.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class SYNQAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "SYNQAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Event processing not required for remote control
    }

    override fun onInterrupt() {
        Log.w(TAG, "SYNQAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
        }
        Log.i(TAG, "SYNQAccessibilityService destroyed")
    }

    companion object {
        private const val TAG = "SYNQAccessibility"

        @Volatile
        var instance: SYNQAccessibilityService? = null
            private set

        private var lastFocusedHash: Int = 0
        private var currentBuffer: String = ""
        private var isAllSelected: Boolean = false

        fun dispatchScroll(anchorX: Float, anchorY: Float, moveX: Float, moveY: Float, durationMs: Long = 85L): Boolean {
            val service = instance ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

            val totalDist = kotlin.math.hypot(moveX.toDouble(), moveY.toDouble()).toFloat()
            if (totalDist < 25f) return false

            val dm = service.resources.displayMetrics
            val screenW = dm.widthPixels.toFloat()
            val screenH = dm.heightPixels.toFloat()

            // Safe central anchor: guarantees touch down lands in the scroll viewport,
            // never on edge buttons, drawer toggles, or floating cards under the cursor
            val startX = anchorX.coerceIn(screenW * 0.25f, screenW * 0.75f)
            val startY = anchorY.coerceIn(screenH * 0.30f, screenH * 0.70f)
            val endX = (startX + moveX).coerceIn(0f, screenW)
            val endY = (startY + moveY).coerceIn(0f, screenH)

            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return service.dispatchGesture(gesture, null, null)
        }

        fun performNavAction(action: String): Boolean {
            val service = instance ?: return false
            return when (action.lowercase()) {
                "back" -> service.performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> service.performGlobalAction(GLOBAL_ACTION_HOME)
                "recents" -> service.performGlobalAction(GLOBAL_ACTION_RECENTS)
                "notifications" -> service.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                "lock" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                    } else false
                }
                else -> false
            }
        }

        fun dispatchTap(x: Float, y: Float): Boolean {
            val service = instance ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

            lastFocusedHash = 0
            currentBuffer = ""
            isAllSelected = false

            val path = Path().apply {
                moveTo(x, y)
                lineTo(x, y)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 50)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return service.dispatchGesture(gesture, null, null)
        }

        fun dispatchLongPress(x: Float, y: Float): Boolean {
            val service = instance ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

            val path = Path().apply {
                moveTo(x, y)
                lineTo(x, y)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 700)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return service.dispatchGesture(gesture, null, null)
        }

        fun dispatchDoubleTap(x: Float, y: Float): Boolean {
            val service = instance ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

            val path = Path().apply {
                moveTo(x, y)
                lineTo(x, y)
            }
            val stroke1 = GestureDescription.StrokeDescription(path, 0, 40)
            val stroke2 = GestureDescription.StrokeDescription(path, 90, 40)
            val gesture = GestureDescription.Builder()
                .addStroke(stroke1)
                .addStroke(stroke2)
                .build()
            return service.dispatchGesture(gesture, null, null)
        }

        fun dispatchSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 200): Boolean {
            val service = instance ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return service.dispatchGesture(gesture, null, null)
        }

        fun inputText(text: String): Boolean {
            val service = instance ?: return false
            val root = service.rootInActiveWindow ?: return false
            val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false

            val nodeHash = focus.hashCode()
            if (nodeHash != lastFocusedHash) {
                lastFocusedHash = nodeHash
                val isHint = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focus.isShowingHintText)
                val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) focus.hintText?.toString() else null
                val desc = focus.contentDescription?.toString()
                val rawText = focus.text?.toString() ?: ""

                val looksLikePlaceholder = isHint ||
                    (hint != null && (rawText.equals(hint, ignoreCase = true) || rawText.contains(hint))) ||
                    (desc != null && rawText.equals(desc, ignoreCase = true))

                currentBuffer = if (looksLikePlaceholder) "" else rawText
            }

            if (isAllSelected) {
                isAllSelected = false
                currentBuffer = text
            } else {
                currentBuffer += text
            }
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, currentBuffer)
            }
            return focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        fun handleKey(key: String): Boolean {
            val service = instance ?: return false
            val root = service.rootInActiveWindow ?: return false
            val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false

            val nodeHash = focus.hashCode()
            if (nodeHash != lastFocusedHash) {
                lastFocusedHash = nodeHash
                val isHint = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focus.isShowingHintText)
                val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) focus.hintText?.toString() else null
                val desc = focus.contentDescription?.toString()
                val rawText = focus.text?.toString() ?: ""

                val looksLikePlaceholder = isHint ||
                    (hint != null && (rawText.equals(hint, ignoreCase = true) || rawText.contains(hint))) ||
                    (desc != null && rawText.equals(desc, ignoreCase = true))

                currentBuffer = if (looksLikePlaceholder) "" else rawText
            }

            return when (key.lowercase()) {
                "backspace", "delete" -> {
                    if (isAllSelected) {
                        isAllSelected = false
                        currentBuffer = ""
                        val args = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                        }
                        focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    } else if (currentBuffer.isNotEmpty()) {
                        currentBuffer = currentBuffer.dropLast(1)
                        val args = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, currentBuffer)
                        }
                        focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    } else {
                        val isHint = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focus.isShowingHintText)
                        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) focus.hintText?.toString() else null
                        val desc = focus.contentDescription?.toString()
                        val rawText = focus.text?.toString() ?: ""
                        val looksLikePlaceholder = isHint ||
                            (hint != null && (rawText.equals(hint, ignoreCase = true) || rawText.contains(hint))) ||
                            (desc != null && rawText.equals(desc, ignoreCase = true))

                        if (!looksLikePlaceholder && rawText.isNotEmpty()) {
                            val newText = rawText.dropLast(1)
                            currentBuffer = newText
                            val args = Bundle().apply {
                                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
                            }
                            focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        } else false
                    }
                }
                "shift_enter" -> {
                    inputText("\n")
                }
                "enter" -> {
                    isAllSelected = false
                    currentBuffer = ""
                    lastFocusedHash = 0

                    var handled = false
                    // 1. Try IME action on the focused node (API 30+)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        handled = focus.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                    }

                    // 2. If not handled, search active window for a "Send", "Search", or "Submit" button
                    if (!handled) {
                        val sendNode = findSendOrSubmitNode(root)
                        if (sendNode != null) {
                            handled = sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                    }

                    // 3. Fallback to click on focus
                    if (!handled) {
                        focus.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    handled
                }
                else -> false
            }
        }

        private fun findSendOrSubmitNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            if (node.isClickable && (text == "send" || desc == "send" || text == "search" || desc == "search" || text == "submit" || desc == "submit")) {
                return node
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = findSendOrSubmitNode(child)
                if (found != null) return found
            }
            return null
        }

        fun handleShortcut(shortcut: String): Boolean {
            val service = instance ?: return false
            val root = service.rootInActiveWindow ?: return false
            val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false

            return when (shortcut.lowercase()) {
                "select_all" -> {
                    val len = focus.text?.length ?: 0
                    val args = Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
                    }
                    isAllSelected = true
                    focus.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
                }
                "copy" -> {
                    focus.performAction(AccessibilityNodeInfo.ACTION_COPY)
                }
                "cut" -> {
                    isAllSelected = false
                    currentBuffer = ""
                    focus.performAction(AccessibilityNodeInfo.ACTION_CUT)
                }
                "paste" -> {
                    isAllSelected = false
                    focus.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                }
                "undo" -> false
                else -> false
            }
        }

        fun setSoftKeyboardHidden(hidden: Boolean) {
            val service = instance ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                runCatching {
                    service.softKeyboardController.showMode = if (hidden) {
                        AccessibilityService.SHOW_MODE_HIDDEN
                    } else {
                        AccessibilityService.SHOW_MODE_AUTO
                    }
                }
            }
        }
    }
}

