package com.iamkaf.multiloader.support

import groovy.lang.Closure
import org.codehaus.groovy.runtime.InvokerHelper

object GroovyGradleDsl {
    @JvmStatic
    fun invoke(target: Any, method: String, vararg args: Any?): Any? =
        InvokerHelper.invokeMethod(target, method, args)

    @JvmStatic
    fun get(target: Any, property: String): Any? =
        InvokerHelper.getProperty(target, property)

    @JvmStatic
    fun set(target: Any, property: String, value: Any?) {
        InvokerHelper.setProperty(target, property, value)
    }

    @JvmStatic
    fun closure(action: (Any) -> Unit): Closure<Unit> =
        object : Closure<Unit>(GroovyGradleDsl) {
            @Suppress("unused")
            fun doCall() {
                action(delegate)
            }

            @Suppress("unused")
            fun doCall(arg: Any?) {
                action(arg ?: delegate)
            }
        }.also {
            it.resolveStrategy = Closure.DELEGATE_FIRST
        }
}
