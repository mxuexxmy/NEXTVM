package com.nextvm.core.binder.proxy

import timber.log.Timber
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method

/**
 * ActivityTaskManagerProxy — Intercepts IActivityTaskManager Binder calls.
 *
 * On Android 10+, [android.app.Activity.startActivity] goes through
 * Instrumentation.execStartActivity → IActivityTaskManager.startActivity,
 * not IActivityManager. Without this proxy, guest-to-guest launches of
 * non-exported activities resolve against a host install of the same package
 * and crash with SecurityException (uid mismatch / not exported).
 *
 * startActivity* handling is delegated to [ActivityManagerProxy.handleStartActivityVia]
 * so Intent rewrite / callingPackage fix / permission-settings interception stay shared.
 */
class ActivityTaskManagerProxy(
    private val original: Any,
    private val amProxy: ActivityManagerProxy,
) : InvocationHandler {

    companion object {
        private const val TAG = "ATMProxy"
    }

    override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
        return try {
            when {
                method.name.startsWith("startActivity") -> {
                    Timber.tag(TAG).d("IActivityTaskManager.${method.name}")
                    amProxy.handleStartActivityVia(method, args, original)
                }
                else -> {
                    if (args != null) method.invoke(original, *args)
                    else method.invoke(original)
                }
            }
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException ?: e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error in ATM proxy for ${method.name}")
            try {
                if (args != null) method.invoke(original, *args)
                else method.invoke(original)
            } catch (e2: Exception) {
                Timber.tag(TAG).e(e2, "ATM original invoke also failed for ${method.name}")
                defaultValue(method.returnType)
            }
        }
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        Void.TYPE, Void::class.java -> null
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        else -> null
    }
}
