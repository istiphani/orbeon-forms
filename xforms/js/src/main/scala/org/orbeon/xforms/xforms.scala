/**
  * Copyright (C) 2017 Orbeon, Inc.
  *
  * This program is free software you can redistribute it and/or modify it under the terms of the
  * GNU Lesser General Public License as published by the Free Software Foundation either version
  *  2.1 of the License, or (at your option) any later version.
  *
  * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY
  * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
  * See the GNU Lesser General Public License for more details.
  *
  * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
  */
package org.orbeon.xforms

import org.scalajs.dom.html.Element
import org.scalajs.dom.raw.HTMLElement
import org.scalajs.jquery.JQueryCallback

import scala.scalajs.js
import scala.scalajs.js.annotation.{JSName, ScalaJSDefined}
import scala.scalajs.js.|

@js.native
trait Item extends js.Object {
  val label      : String                     = js.native
  val value      : String                     = js.native
  val attributes : js.UndefOr[ItemAttributes] = js.native
  val children   : js.UndefOr[js.Array[Item]] = js.native
}

@js.native
trait ItemAttributes extends js.Object {
  val `class`        : js.UndefOr[String] = js.native
  val style          : js.UndefOr[String] = js.native
  val `xxforms-open` : js.UndefOr[String] = js.native
}

@ScalaJSDefined
class XBLCompanion extends js.Object {

  // Lifecycle

  def init()                                  : Unit   = ()
  def destroy()                               : Unit   = ()

  def xformsGetValue()                        : String = null
  def xformsUpdateValue(newValue: String)     : Unit   = ()
  def xformsUpdateReadonly(readonly: Boolean) : Unit   = ()
  def xformsFocus()                           : Unit   = ()

  // Helpers

  def containerElem: Element = this.asInstanceOf[js.Dynamic].container.asInstanceOf[Element]
}

@JSName("ORBEON.xforms.XBL")
@js.native
object XBL extends js.Object {
  def declareCompanion(name: String, companion: XBLCompanion): Unit = js.native
}

@js.native
trait YUICustomEvent extends js.Object {
  def subscribe(fn: js.Function)   : Unit = js.native
  def unsubscribe(fn: js.Function) : Unit = js.native
}

@JSName("YAHOO.util.Event")
@js.native
object Event extends js.Object {
  def preventDefault(event: js.Object): Unit = js.native
}

@JSName("ORBEON.xforms.Events")
@js.native
object Events extends js.Object {
  def ajaxResponseProcessedEvent: YUICustomEvent = js.native
}

@JSName("ORBEON.xforms.server.AjaxServer")
@js.native
object AjaxServer extends js.Object {
  def ajaxResponseReceived: JQueryCallback = js.native
}

@JSName("ORBEON.xforms.Document")
@js.native
object Document extends js.Object {
  def dispatchEvent(event: js.Object): Unit = js.native
  def setValue(controlIdOrElem: String | Element, newValue: String, form: js.UndefOr[Element] = js.undefined): Unit = js.native
  def getValue(controlIdOrElem: String | Element, form: js.UndefOr[Element] = js.undefined): js.UndefOr[String] = js.native
}

@JSName("ORBEON.util.ExecutionQueue")
@js.native
class ExecutionQueue extends js.Object {
  def add(event: js.Object, waitMs: Int, waitType: Int): Unit = js.native
}

@JSName("ORBEON.util.ExecutionQueue")
@js.native
object ExecutionQueue extends js.Object {
  val MIN_WAIT: Int = js.native
  val MAX_WAIT: Int = js.native
}

@JSName("ORBEON.xforms.server.UploadServer")
@js.native
object UploadServer extends js.Object {
  val uploadEventQueue: ExecutionQueue = js.native
  def cancel(doAbort: Boolean, event: String): Unit = js.native
}

@JSName("ORBEON.util.Property")
@js.native
class Property[T] extends js.Object {
  def get(): T = js.native
}

@JSName("ORBEON.util.Properties")
@js.native
object Properties extends js.Object {
  val delayBeforeIncrementalRequest    : Property[Int] = js.native
  val delayBeforeUploadProgressRefresh : Property[Int] = js.native
}

@JSName("ORBEON.util.Utils")
@js.native
object Utils extends js.Object {
  def appendToEffectiveId(effectiveId: String, ending: String): String = js.native
}

@JSName("ORBEON.xforms.Page")
@js.native
object Page extends js.Object {
  def registerControlConstructor(controlConstructor: js.Function0[Control], predicate: js.Function1[HTMLElement, Boolean]): Unit = js.native
}

@JSName("ORBEON.xforms.control.Control")
@js.native
class Control extends js.Object {

  val container: HTMLElement = js.native

  def init(container: Element) : Unit    = js.native
  def getForm()                : Element = js.native
  def change()                 : Unit    = js.native

  // Provide a new itemset for a control, if the control supports this.
  def setItemset(itemset: js.Any): Unit = js.native

  // Set the current value of the control in the UI, without sending an update to the server about the new value
  def setValue(value: String): Unit = js.native

  // Return the current value of the control
  def getValue(): String = js.native

  // Return elements with the given class name that are inside this control
  def getElementsByClassName(className: String): js.Array[Element] = js.native

  // Returns the first element with the given class name that are inside this control
  def getElementByClassName(className: String): js.UndefOr[Element] = js.native
}