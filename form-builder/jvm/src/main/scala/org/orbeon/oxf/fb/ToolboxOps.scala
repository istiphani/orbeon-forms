/**
 * Copyright (C) 2011 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.fb

import enumeratum.EnumEntry.Lowercase
import enumeratum.{Enum, EnumEntry}
import org.orbeon.datatypes.Coordinate1
import org.orbeon.dom.saxon.DocumentWrapper
import org.orbeon.oxf.fb.FormBuilder.{findNestedContainers, _}
import org.orbeon.oxf.fb.XMLNames._
import org.orbeon.oxf.fr.FormRunner._
import org.orbeon.oxf.fr.NodeInfoCell._
import org.orbeon.oxf.fr.XMLNames._
import org.orbeon.oxf.fr._
import org.orbeon.oxf.pipeline.Transform
import org.orbeon.oxf.util.CoreUtils._
import org.orbeon.oxf.util.XPath
import org.orbeon.oxf.xforms.NodeInfoFactory._
import org.orbeon.oxf.xforms.action.XFormsAPI
import org.orbeon.oxf.xforms.action.XFormsAPI.{insert, _}
import org.orbeon.oxf.xml.{TransformerUtils, XMLConstants}
import org.orbeon.saxon.om.NodeInfo
import org.orbeon.scaxon.Implicits._
import org.orbeon.scaxon.NodeConversions._
import org.orbeon.scaxon.SimplePath._

import scala.collection.mutable


/*
 * Form Builder: toolbox operations.
 */
object ToolboxOps {

  import Private._

  // Insert a new control in a cell
  //@XPathFunction
  def insertNewControl(doc: NodeInfo, binding: NodeInfo): Option[String] = {

    implicit val ctx = FormBuilderDocContext()

    ensureEmptyCell() match {
      case Some(gridTd) ⇒
        withDebugGridOperation("insert new control") {
          val newControlName = controlNameFromId(nextId("control"))

          // Insert control template
          val newControlElem: NodeInfo =
            findViewTemplate(binding) match {
              case Some(viewTemplate) ⇒
                // There is a specific template available
                val controlElem = insert(into = gridTd, origin = viewTemplate).head
                // xf:help might be in the template, but we don't need it as it is created on demand
                delete(controlElem / "help")
                controlElem
              case _ ⇒
                // No specific, create simple element with LHHA
                val controlElem =
                  insert(
                    into   = gridTd,
                    origin = elementInfo(bindingFirstURIQualifiedName(binding))
                  ).head

                insert(
                  into   = controlElem,
                  origin = lhhaTemplate / *
                )

                controlElem
            }

          // Set default pointer to resources if there is an xf:alert
          setvalue(newControlElem / "*:alert" /@ "ref", OldStandardAlertRef)

          // Data holder may contain file attributes
          val dataHolder = newDataHolder(newControlName, binding)

          // Create resource holder for all form languages
          val resourceHolders = {
            val formLanguages = FormRunnerResourcesOps.allLangs(ctx.resourcesRootElem)
            formLanguages map { formLang ⇒

              // Elements for LHHA resources, only keeping those referenced from the view (e.g. a button has no hint)
              val lhhaResourceEls = {
                val lhhaNames = newControlElem / * map (_.localname) filter LHHAResourceNamesToInsert
                lhhaNames map (elementInfo(_))
              }

              // Resource holders from XBL metadata
              val xblResourceEls = binding / "*:metadata" / "*:templates" / "*:resources" / *

              // Template items, if needed
              val itemsResourceEls =
                if (hasEditor(newControlElem, "static-itemset")) {
                  val fbResourceInFormLang = FormRunnerLang.formResourcesInLang(formLang)
                  val originalTemplateItems = fbResourceInFormLang / "template" / "items" / "item"
                  if (hasEditor(newControlElem, "item-hint")) {
                    // Supports hint: keep hint we have in the resources.xml
                    originalTemplateItems
                  }  else {
                    // Hint not supported: <hint> in each <item>
                    originalTemplateItems map { item ⇒
                      val newLHHA = (item / *) filter (_.localname != "hint")
                      elementInfo("item", newLHHA)
                    }
                  }
                } else {
                  Nil
                }

              val resourceEls = lhhaResourceEls ++ xblResourceEls ++ itemsResourceEls
              formLang → List(elementInfo(newControlName, resourceEls))
            }
          }

          // Insert data and resource holders
          insertHolders(
            controlElement       = newControlElem,
            dataHolders          = List(dataHolder),
            resourceHolders      = resourceHolders,
            precedingControlName = precedingBoundControlNameInSectionForControl(newControlElem)
          )

          // Adjust bindings on newly inserted control, done after the control is added as
          // renameControlByElement() expects resources to be present
          renameControlByElement(newControlElem, newControlName, resourceNamesInUseForControl(newControlName))

          // Insert the bind element
          val bind = ensureBinds(findContainerNamesForModel(gridTd) :+ newControlName)

          // Make sure there is a @bind instead of a @ref on the control
          delete(newControlElem /@ "ref")
          ensureAttribute(newControlElem, "bind", bind.id)

          // Set bind attributes if any
          insert(into = bind, origin = findBindAttributesTemplate(binding))

          // This can impact templates
          updateTemplatesCheckContainers(findAncestorRepeatNames(gridTd).to[Set])

          Some(newControlName)
        }
      case _ ⇒
        // no empty td found/created so NOP
        None
    }
  }

  // TODO: Review these. They are probably not needed as of 2017-10-12.
  //@XPathFunction
  def canInsertSection(inDoc: NodeInfo) = inDoc ne null
  //@XPathFunction
  def canInsertGrid   (inDoc: NodeInfo) = (inDoc ne null) && findSelectedCell(FormBuilderDocContext(inDoc)).isDefined
  //@XPathFunction
  def canInsertControl(inDoc: NodeInfo) = (inDoc ne null) && willEnsureEmptyCellSucceed(FormBuilderDocContext(inDoc))

  // Insert a new grid
  //@XPathFunction
  def insertNewGrid(inDoc: NodeInfo): Unit = {

    implicit val ctx = FormBuilderDocContext()

    withDebugGridOperation("insert new grid") {

      val (into, after, _) = findGridInsertionPoint

      // Obtain ids first
      val ids = nextTmpIds(count = 2).toIterator

      // The grid template
      val gridTemplate: NodeInfo =
        <fr:grid edit-ref="" id={nextId("grid")} xmlns:fr="http://orbeon.org/oxf/xml/form-runner">
          <fr:c id={ids.next()} x="1" y="1" w="6"/><fr:c id={ids.next()} x="7" y="1" w="6"/>
        </fr:grid>

      // Insert after current level 2 if found, otherwise into level 1
      val newGridElem = insert(into = into, after = after.toList, origin = gridTemplate).head

      // This can impact templates
      updateTemplatesCheckContainers(findAncestorRepeatNames(into, includeSelf = true).to[Set])

      // Select first grid cell
      selectFirstCellInContainer(newGridElem)
    }
  }

  // Insert a new section with optionally a nested grid
  //@XPathFunction
  def insertNewSection(inDoc: NodeInfo, withGrid: Boolean): Some[NodeInfo] = {

    implicit val ctx = FormBuilderDocContext()

    withDebugGridOperation("insert new section") {

      val (into, after) = findSectionInsertionPoint

      val newSectionName = controlNameFromId(nextId("section"))
      val precedingSectionName = after flatMap getControlNameOpt

      // Obtain ids first
      val ids = nextTmpIds(count = 2).toIterator

      // NOTE: use xxf:update="full" so that xxf:dynamic can better update top-level XBL controls
      val sectionTemplate: NodeInfo =
        <fr:section id={sectionId(newSectionName)} bind={bindId(newSectionName)} edit-ref="" xxf:update="full"
              xmlns:xh="http://www.w3.org/1999/xhtml"
              xmlns:xf="http://www.w3.org/2002/xforms"
              xmlns:xxf="http://orbeon.org/oxf/xml/xforms"
              xmlns:fb="http://orbeon.org/oxf/xml/form-builder"
              xmlns:fr="http://orbeon.org/oxf/xml/form-runner">
          <xf:label ref={s"$$form-resources/$newSectionName/label"}/>{
          if (withGrid)
            <fr:grid edit-ref="" id={nextId("grid")}>
              <fr:c id={ids.next()} x="1" y="1" w="6"/><fr:c id={ids.next()} x="7" y="1" w="6"/>
            </fr:grid>
        }</fr:section>

      val newSectionElem = insert(into = into, after = after.toList, origin = sectionTemplate).head

      // Create and insert holders
      val resourceHolder = {
        val elemContent = List(elementInfo("label"), elementInfo("help"))
        elementInfo(newSectionName, elemContent)
      }

      insertHolderForAllLang(
        controlElement       = newSectionElem,
        dataHolder           = elementInfo(newSectionName),
        resourceHolder       = resourceHolder,
        precedingControlName = precedingSectionName
      )

      // Insert the bind element
      ensureBinds(findContainerNamesForModel(newSectionElem, includeSelf = true))

      // This can impact templates
      updateTemplatesCheckContainers(findAncestorRepeatNames(into, includeSelf = true).to[Set])

      // Select first grid cell
      if (withGrid)
        selectFirstCellInContainer(newSectionElem)

      // TODO: Open label editor for newly inserted section

      Some(newSectionElem)
    }
  }

  // Insert a new repeat
  //@XPathFunction
  def insertNewRepeatedGrid(inDoc: NodeInfo): Some[String] = {

    implicit val ctx = FormBuilderDocContext()

    withDebugGridOperation("insert new repeat") {

      val (into, after, grid) = findGridInsertionPoint
      val newGridName         = controlNameFromId(nextId("grid"))

      val ids = nextTmpIds(count = 2).toIterator

      // The grid template
      val gridTemplate: NodeInfo =
        <fr:grid
           edit-ref=""
           id={gridId(newGridName)}
           bind={bindId(newGridName)}
           xmlns:fr="http://orbeon.org/oxf/xml/form-runner">
          <fr:c id={ids.next()} x="1" y="1" w="6"/><fr:c id={ids.next()} x="7" y="1" w="6"/>
        </fr:grid>

      // Insert grid
      val newGridElem = insert(into = into, after = after.toList, origin = gridTemplate).head

      // Insert instance holder (but no resource holders)
      insertHolders(
        controlElement       = newGridElem,
        dataHolders          = List(elementInfo(newGridName)),
        resourceHolders      = Nil,
        precedingControlName = grid flatMap (precedingBoundControlNameInSectionForGrid(_, includeSelf = true))
      )

      // Make sure binds are created
      ensureBinds(findContainerNamesForModel(newGridElem, includeSelf = true))

      // This takes care of all the repeat-related items
      setRepeatProperties(
        controlName          = newGridName,
        repeat               = true,
        min                  = "1",
        max                  = "",
        iterationNameOrEmpty = "",
        applyDefaults        = true,
        initialIterations    = "first"
      )

      // Select new td
      selectFirstCellInContainer(newGridElem)

      Some(newGridName)
    }
  }

  def selectFirstCellInContainer(containerElem: NodeInfo)(implicit ctx: FormBuilderDocContext): Unit =
    (containerElem descendant Cell.CellTestName headOption) foreach selectCell

  // Insert a new section template
  //@XPathFunction
  def insertNewSectionTemplate(inDoc: NodeInfo, binding: NodeInfo): Unit = {

    implicit val ctx = FormBuilderDocContext()

    // Insert new section first
    insertNewSection(inDoc, withGrid = false) foreach { section ⇒

      val selector = binding attValue "element"

      val xbl              = ctx.modelElem followingSibling XBLXBLTest
      val existingBindings = xbl child XBLBindingTest

      // Insert binding into form if needed
      if (! (existingBindings /@ "element" === selector))
        insert(after = ctx.modelElem +: xbl, origin = binding parent * )

      // Insert template into section
      findViewTemplate(binding) foreach
        (template ⇒ insert(into = section, after = section / *, origin = template))
    }
  }

  /* Example layout:
  <xcv>
    <control>
      <xf:input id="control-1-control" bind="control-1-bind">
        <xf:label ref="$form-resources/control-1/label"/>
        <xf:hint ref="$form-resources/control-1/hint"/>
        <xf:alert ref="$fr-resources/detail/labels/alert"/>
      </xf:input>
    </control>
    <holder>
      <control-1/>
    </holder>
    <resources>
      <resource xml:lang="en">
        <control-1>
          <label>My label</label>
          <hint/>
          <alert/>
        </control-1>
      </resource>
    </resources>
    <bind>
      <xf:bind id="control-1-bind" name="control-1" ref="control-1"/>
    </bind>
  </xcv>
  */

  sealed abstract class XcvEntry extends EnumEntry with Lowercase
  object XcvEntry extends Enum[XcvEntry] {
    val values = findValues
    case object Control   extends XcvEntry
    case object Holder    extends XcvEntry
    case object Resources extends XcvEntry
    case object Bind      extends XcvEntry
  }


  def controlOrContainerElemToXcv(controlOrContainerElem: NodeInfo)(implicit ctx: FormBuilderDocContext): NodeInfo = {

    val resourcesRootElem = resourcesRoot

    val controlDetailsOpt =
      searchControlBindPathHoldersInDoc(
        controlElems   = List(controlOrContainerElem),
        inDoc          = ctx.formDefinitionRootElem,
        contextItemOpt = Some(ctx.dataRootElem),
        predicate      = _ ⇒ true
      ).headOption

    val xcvContent =
      controlDetailsOpt match {
        case Some(ControlBindPathHoldersResources(control, bind, _, holders, _)) ⇒
          // The control has a name and a bind

          val bindAsList = List(bind)

          // Handle resources separately since unlike holders and binds, they are not nested
          val resourcesWithLang =
            for {
              rootBind ← bindAsList
              lang     ← FormRunnerResourcesOps.allLangs(resourcesRootElem)
            } yield
              elementInfo(
                "resource",
                attributeInfo(XMLLangQName, lang) ++
                  FormBuilder.iterateSelfAndDescendantBindsResourceHolders(rootBind, lang, resourcesRootElem)
              )

          // LATER: handle repetitions, for now keep only the first data holder
          val firstHolderOpt = holders flatMap (_.headOption)

          XcvEntry.values map {
            case e @ XcvEntry.Control   ⇒ e → List(control)
            case e @ XcvEntry.Holder    ⇒ e → firstHolderOpt.toList
            case e @ XcvEntry.Resources ⇒ e → resourcesWithLang
            case e @ XcvEntry.Bind      ⇒ e → bindAsList
          }

        case None ⇒
          // Non-repeated grids don't have a name or a bind.
          // In this case, we use the grid control as a source of truth and find the nested controls.

          val nestedControlDetails = searchControlBindPathHoldersInDoc(
            controlElems   = findNestedControls(controlOrContainerElem),
            inDoc          = ctx.formDefinitionRootElem,
            contextItemOpt = Some(ctx.dataRootElem),
            predicate      = _ ⇒ true
          )

          val resourcesWithLang = nestedControlDetails flatMap (_.resources) groupBy (_._1) map {
            case (lang, langsAndElems) ⇒
              elementInfo(
                "resource",
                attributeInfo(XMLLangQName, lang) ++ (langsAndElems map (_._2))
              )
          }

          XcvEntry.values map {
            case e @ XcvEntry.Control   ⇒ e → List(controlOrContainerElem)
            case e @ XcvEntry.Holder    ⇒ e → (nestedControlDetails flatMap (_.holders flatMap (_.headOption)))
            case e @ XcvEntry.Resources ⇒ e → resourcesWithLang.toList
            case e @ XcvEntry.Bind      ⇒ e → (nestedControlDetails map (_.bind))
          }
      }

    val result = elementInfo("xcv", xcvContent map { case (xcvEntry, content) ⇒ elementInfo(xcvEntry.entryName, content) })

    // Remove all `tmp-*-tmp` attributes as they are transient and, instead of renaming them upon paste,
    // we just re-annotate at that time
    result descendant (FRGridTest || NodeInfoCell.CellTest) att "id" filter
      (a ⇒ a.stringValue.startsWith("tmp-") && a.stringValue.endsWith("-tmp")) foreach (delete(_))

    result
  }

  def controlElementsInCellToXcv(cellElem: NodeInfo)(implicit ctx: FormBuilderDocContext): Option[NodeInfo] = {
    val name  = getControlName(cellElem / * head)
    findControlByName(ctx.formDefinitionRootElem, name) map controlOrContainerElemToXcv
  }

  // Copy control to the clipboard
  //@XPathFunction
  def copyToClipboard(cellElem: NodeInfo): Unit = {

    implicit val ctx = FormBuilderDocContext()

    controlElementsInCellToXcv(cellElem)
      .foreach(writeXcvToClipboard)
  }

  // Cut control to the clipboard
  //@XPathFunction
  def cutToClipboard(cellElem: NodeInfo): Unit = {

    implicit val ctx = FormBuilderDocContext()

    copyToClipboard(cellElem)
    deleteControlWithinCell(cellElem, updateTemplates = true)
  }

  def readXcvFromClipboard(implicit ctx: FormBuilderDocContext): Option[NodeInfo] = {
    val xcvElem = ctx.clipboardXcvRootElem

    (xcvElem / XcvEntry.Control.entryName / * nonEmpty) option {
      val clone = elementInfo("xcv", Nil)
      insert(into = clone, origin = xcvElem / *)
      clone
    }
  }

  def clearClipboard()(implicit ctx: FormBuilderDocContext): Unit =
    XcvEntry.values
      .map(_.entryName)
      .foreach(entryName ⇒ delete(ctx.clipboardXcvRootElem / entryName))

  def writeXcvToClipboard(xcv: NodeInfo)(implicit ctx: FormBuilderDocContext): Unit = {
    clearClipboard()
    insert(into = ctx.clipboardXcvRootElem, origin = xcv / *)
  }

  def dndControl(
    sourceCellElem : NodeInfo,
    targetCellElem : NodeInfo,
    copy           : Boolean)(implicit
    ctx            : FormBuilderDocContext
  ): Unit = {

    val xcvElemOpt = controlElementsInCellToXcv(sourceCellElem)

    xcvElemOpt foreach { x ⇒
      println(TransformerUtils.tinyTreeToString(x))
    }

    withDebugGridOperation("dnd delete") {
    if (! copy)
      deleteControlWithinCell(sourceCellElem, updateTemplates = true)
    }

    withDebugGridOperation("dnd paste") {
    selectCell(targetCellElem)
    xcvElemOpt foreach (pasteSingleControlFromXcv(_, None))
    }
  }

  def namesToRenameForMergingSectionTemplate(
    containerId : String,
    prefix      : String,
    suffix      : String)(implicit
    ctx         : FormBuilderDocContext
  ): Option[Seq[(String, String, Boolean)]] =
    for {
      xcvElem     ← xcvFromSectionWithTemplate(containerId)
      sectionElem ← xcvElem / XcvEntry.Control.entryName firstChildOpt *
      sectionName ← getControlNameOpt(sectionElem)
    } yield
      namesToRenameForPaste(xcvElem, prefix, suffix) filter (_._1 != sectionName)

  def namesToRenameForClipboard(
    prefix      : String,
    suffix      : String)(implicit
    ctx         : FormBuilderDocContext
  ): Option[Seq[(String, String, Boolean)]] =
    readXcvFromClipboard map
      (namesToRenameForPaste(_, prefix, suffix))

  private def namesToRenameForPaste(
    xcvElem : NodeInfo,
    prefix  : String,
    suffix  : String)(implicit
    ctx     : FormBuilderDocContext
  ): Seq[(String, String, Boolean)] = {

    require(xcvElem.isElement)

    val xcvNamesInUse =
      mutable.LinkedHashSet() ++ iterateNamesInUse(Right(xcvElem), xcvElem / XcvEntry.Holder.entryName / * headOption) toList

    def toNameWithPrefixSuffix(name: String) = prefix + name + suffix

    val newControlNamesWithAutomaticIdsMap = {

      val xcvNamesWithPrefixSuffix   = xcvNamesInUse map toNameWithPrefixSuffix
      val needRenameWithAutomaticIds = mutable.LinkedHashSet() ++ xcvNamesWithPrefixSuffix intersect getAllNamesInUse

      // These names are both subsets of `getAllNamesInUse`
      val (allSectionNamesInUse, allGridNamesInUse) = {

        val (allSections, allGrids) =
          findNestedContainers(ctx.bodyElem) partition IsSection

        (allSections flatMap getControlNameOpt toSet, allGrids flatMap getControlNameOpt toSet)
      }

      val newControlNamesIt = nextIds("control", needRenameWithAutomaticIds.size).iterator map controlNameFromId
      val newSectionNamesIt = nextIds("section", allSectionNamesInUse      .size).iterator map controlNameFromId
      val newGridNamesIt    = nextIds("grid",    allGridNamesInUse         .size).iterator map controlNameFromId

      // Produce `section-` and `grid-` for sections and grids
      def newName(name: String) =
        if (allSectionNamesInUse(name))
          newSectionNamesIt.next()
        else if (allGridNamesInUse(name))
          newGridNamesIt.next()
        else
          newControlNamesIt.next()

      needRenameWithAutomaticIds.iterator.map(name ⇒ name → newName(name)).toMap
    }

    xcvNamesInUse map { xcvName ⇒

      val withPrefixSuffix = toNameWithPrefixSuffix(xcvName)
      val automaticIdOpt   = newControlNamesWithAutomaticIdsMap.get(withPrefixSuffix)

      (xcvName, automaticIdOpt getOrElse withPrefixSuffix, automaticIdOpt.isDefined)
    }
  }

  def xcvFromSectionWithTemplate(containerId: String)(implicit ctx: FormBuilderDocContext): Option[NodeInfo] = {

    val containerElem = containerById(containerId)

    // Check this is a section template section
    if (isSectionWithTemplateContent(containerElem)) {

      val head = ctx.formDefinitionRootElem / XHHeadTest head

      xblBindingForSection(head, containerElem) map { bindingDoc ⇒

        val bindingElem = bindingDoc.rootElement

        val containerName       = getControlName(containerElem)
        val sectionTemplateName = bindingFirstURIQualifiedName(bindingElem).localName

        val model = bindingElem / XBLImplementationTest / XFModelTest

        val resourcesWithLangElems = {

          val sectionLangResources    = findResourceHoldersWithLang(containerName, resourcesRoot)
          val sectionLangResourcesMap = sectionLangResources.toMap

          val nestedResources = {

            val resourcesInstanceElem = model / XFInstanceTest find (_.hasIdValue(Names.FormResources))
            val resourceElems         = resourcesInstanceElem.toList flatMap (_ child * child *)

            // NOTE: For some reason, there is a resource with the name of the section template. That should
            // be fixed, but in the meanwhile we need to remove those.
            XFormsAPI.delete(resourceElems / * filter (_.localname == sectionTemplateName))

            resourceElems map (e ⇒ (e attValue XMLLangQName) → (e / *))
          }

          val nestedResourcesMap = nestedResources.toMap

          allResources(ctx.resourcesRootElem) map { resource ⇒

            val lang = resource attValue XMLLangQName

            val sectionHolderForLang = sectionLangResourcesMap.getOrElse(lang, sectionLangResources.head._2)
            val otherHoldersForLang  = nestedResourcesMap.getOrElse(lang, nestedResources.head._2)

            elementInfo(
              "resource",
              attributeInfo(XMLLangQName, lang) ++ sectionHolderForLang ++ otherHoldersForLang
            )
          }
        }

        val newSectionControlElem = {

          val nestedContainers = bindingDoc.rootElement / XBLTemplateTest / * / * filter IsContainer

          val newElem = TransformerUtils.extractAsMutableDocument(containerElem).rootElement

          // NOTE: This duplicates some annotations done in `annotate.xpl`.
          nestedContainers ++ newElem foreach { containerElem ⇒
            XFormsAPI.ensureAttribute(containerElem, "edit-ref", "")
            if (IsSection(containerElem))
              XFormsAPI.ensureAttribute(containerElem, XXF → "update", "full")
          }

          XFormsAPI.delete(newElem / * filter isSectionTemplateContent)
          XFormsAPI.insert(into = newElem, after = newElem / *, origin = nestedContainers)
          newElem
        }

        val newDataHolderElem = {

          val dataTemplateInstanceElem = model / XFInstanceTest find (_.hasIdValue("fr-form-template"))
          val nestedDataHolderElems    = dataTemplateInstanceElem.toList flatMap (_ child * take 1 child *)

          val newElem = elementInfo(containerName)
          XFormsAPI.insert(into = newElem, origin = nestedDataHolderElems)
          newElem
        }

        val newBindElem = {

          val nestedBindElems = model / XFBindTest / *

          val newElem = TransformerUtils.extractAsMutableDocument(findBindByName(ctx.formDefinitionRootElem, containerName).get).rootElement
          XFormsAPI.delete(newElem / *)
          XFormsAPI.insert(into = newElem, origin = nestedBindElems)
          newElem
        }

        val xcvContent =
          XcvEntry.values map {
            case e @ XcvEntry.Control   ⇒ e → List(newSectionControlElem)
            case e @ XcvEntry.Holder    ⇒ e → List(newDataHolderElem)
            case e @ XcvEntry.Resources ⇒ e → resourcesWithLangElems
            case e @ XcvEntry.Bind      ⇒ e → List(newBindElem)
          }

        new DocumentWrapper(
          Transform.transformDocument(
            Transform.FileReadDocument("/forms/orbeon/builder/form/annotate-xcv.xsl"),
            Some(
              Transform.InlineReadDocument(
                "",
                TransformerUtils.tinyTreeToDom4j(
                  elementInfo("xcv", xcvContent map { case (xcvEntry, content) ⇒ elementInfo(xcvEntry.entryName, content) })
                ),
                0L
              )
            ),
            XMLConstants.UNSAFE_XSLT_PROCESSOR_QNAME
          ),
          null,
          XPath.GlobalConfiguration
        ).rootElement

      }
    } else
      None
  }

  def containerMerge(
    containerId : String,
    prefix      : String,
    suffix      : String)(implicit
    ctx         : FormBuilderDocContext
  ): Unit =
    xcvFromSectionWithTemplate(containerId) foreach { xcvElem ⇒
      deleteSectionById(containerId)
      pasteSectionGridFromXcv(xcvElem, prefix, suffix, None)
    }

  // Paste control from the clipboard
  //@XPathFunction
  def pasteFromClipboard(): Unit = {

    implicit val ctx = FormBuilderDocContext()

    readXcvFromClipboard foreach { xcvElem ⇒

      val controlElem = xcvElem / XcvEntry.Control.entryName / * head

      if (IsGrid(controlElem) || IsSection(controlElem)) {

        if (namesToRenameForPaste(xcvElem, "", "") forall (! _._3))
          pasteSectionGridFromXcv(xcvElem, "", "", None)
        else
          XFormsAPI.dispatch(
            name       = "fb-show-dialog",
            targetId   = "dialog-ids",
            properties = Map("container-id" → Some(controlElem.id), "action" → Some("paste"))
          )
      } else
        pasteSingleControlFromXcv(xcvElem, None)
    }
  }

  private val ControlResourceNames = Set.empty ++ LHHAInOrder + "itemset"

  def pasteSectionGridFromXcv(
    xcvElem        : NodeInfo,
    prefix         : String,
    suffix         : String,
    insertPosition : Option[ContainerPosition])(implicit
    ctx            : FormBuilderDocContext
  ): Unit = {

    require(xcvElem.isElement)

    val containerControlElem = xcvElem / XcvEntry.Control.entryName / * head

    // Rename control names if needed
    locally {

      val oldToNewNames =
        namesToRenameForPaste(xcvElem, prefix, suffix) collect {
          case (oldName, newName, _) if oldName != newName ⇒ oldName → newName
        } toMap

      if (oldToNewNames.nonEmpty) {

        // Rename self control, nested sections and grids, and nested controls
        (getControlNameOpt(containerControlElem).isDefined iterator containerControlElem) ++
          findNestedContainers(containerControlElem).iterator                             ++
          findNestedControls(containerControlElem).iterator foreach { controlElem ⇒

          val oldName = controlNameFromId(controlElem.id)

          oldToNewNames.get(oldName) foreach { newName ⇒
            renameControlByElement(controlElem, newName, ControlResourceNames)
          }
        }

        // Rename holders
        (xcvElem / XcvEntry.Holder.entryName / *).iterator flatMap iterateSelfAndDescendantHoldersReversed foreach { holderElem ⇒

          val oldName = holderElem.localname

          val isDefaultIterationName = oldName.endsWith(DefaultIterationSuffix)

          val oldNameAdjusted =
            if (isDefaultIterationName)
              oldName.substring(0, oldName.size - DefaultIterationSuffix.size)
            else
              oldName

          oldToNewNames.get(oldNameAdjusted) foreach { newName ⇒
            rename(holderElem, if (isDefaultIterationName) newName + DefaultIterationSuffix else newName)
          }
        }

        // Rename resources
        val resourceHolders = xcvElem / XcvEntry.Resources.entryName / "resource" / *

        resourceHolders foreach { holderElem ⇒

          val oldName = holderElem.localname

          oldToNewNames.get(oldName) foreach { newName ⇒
            rename(holderElem, newName)
          }
        }

        // Rename binds
        (xcvElem / XcvEntry.Bind.entryName / *).iterator flatMap iterateSelfAndDescendantBindsReversed foreach { bindElem ⇒

          val oldName = findBindName(bindElem).get

          val isDefaultIterationName = oldName.endsWith(DefaultIterationSuffix)

          val oldNameAdjusted =
            if (isDefaultIterationName)
              oldName.substring(0, oldName.size - DefaultIterationSuffix.size)
            else
              oldName

          oldToNewNames.get(oldNameAdjusted) foreach { newName ⇒
            renameBindElement(bindElem, if (isDefaultIterationName) newName + DefaultIterationSuffix else newName)
          }
        }
      }
    }

    // Rename validation ids if needed
    // NOTE: These are not names so do not really need to be stable.
    locally {

      // Rename nested element ids and alert ids
      val nestedBindElemsWithValidationId =
        for {
          nestedElem   ← xcvElem / XcvEntry.Bind.entryName descendant XFBindTest child NestedBindElemTest
          validationId ← nestedElem.idOpt
        } yield
          nestedElem → validationId

      val oldIdToNewId =
        nestedBindElemsWithValidationId map (_._2) zip nextTmpIds(token = Names.Validation, count = nestedBindElemsWithValidationId.size) toMap

      // Update nested element ids, in particular xf:constraint/@id
      nestedBindElemsWithValidationId foreach { case (nestedElem, oldId) ⇒
        setvalue(nestedElem att "id", oldIdToNewId(oldId))
      }

      val alertsWithValidationId =
        for {
          alertElem    ← xcvElem / XcvEntry.Control.entryName descendant (XF → "alert")
          validationId ← alertElem attValueOpt Names.Validation
        } yield
          alertElem → validationId

      // Update xf:alert/@validation and xf:constraint/@id
      alertsWithValidationId foreach { case (alertWithValidation, oldValidationId) ⇒

        val newValidationIdOpt = oldIdToNewId.get(oldValidationId)

        newValidationIdOpt foreach { newValidationId ⇒
          setvalue(alertWithValidation att Names.Validation, newValidationId)
        }
      }
    }

    val (intoContainerElem, afterElemOpt) =
      insertPosition match {
        case Some(ContainerPosition(into, after)) ⇒

          val intoContainerElem     = into  flatMap (findControlByName(ctx.formDefinitionRootElem, _))
          val afterContainerElemOpt = after flatMap (findControlByName(ctx.formDefinitionRootElem, _))

          // Tricky: Within the `fb-body` top-level container, we need to insert after the `<xf:var>`.
          val afterElemOpt =
            if (intoContainerElem.isEmpty)
              afterContainerElemOpt orElse (ctx.bodyElem lastChildOpt "*:var")
            else
              afterContainerElemOpt

          (intoContainerElem getOrElse ctx.bodyElem, afterElemOpt)
        case None if IsGrid(containerControlElem) ⇒
          val (into, after, _) = findGridInsertionPoint
          (into, after)
        case None ⇒
          findSectionInsertionPoint
      }

    // NOTE: Now non-repeated grids also have a control name.
    val precedingContainerNameOpt = afterElemOpt flatMap getControlNameOpt

    val newContainerElem =
      insert(into = intoContainerElem, after = afterElemOpt.toList, origin = containerControlElem).head

    val resourceHolders =
      for {
        resourceElem ← xcvElem / XcvEntry.Resources.entryName / "resource"
        lang = resourceElem attValue "*:lang"
      } yield
        lang → (resourceElem / *)

    // Insert holders
    insertHolders(
      controlElement       = newContainerElem, // in order to find containers
      dataHolders          = xcvElem / XcvEntry.Holder.entryName / *,
      resourceHolders      = resourceHolders,
      precedingControlName = precedingContainerNameOpt
    )
    val xcvBinds = xcvElem / XcvEntry.Bind.entryName / *

    if (newContainerElem.hasAtt("bind")) {
      // Insert the bind element for the container and descendants
      val tmpBind = ensureBinds(findContainerNamesForModel(newContainerElem, includeSelf = true))
      insert(after = tmpBind, origin = xcvBinds)
      delete(tmpBind)
    } else if (xcvBinds.nonEmpty) {
      // There are descendant binds (case of an unbound non-repeated grid containing at least one control)
      val tmpBind = ensureBinds(findContainerNamesForModel(newContainerElem, includeSelf = false) :+ getControlName(xcvBinds.head))
      insert(after = tmpBind, origin = xcvBinds)
      delete(tmpBind)
    }

    // Insert template for repeated grids/sections
    (getControlNameOpt(containerControlElem).isDefined iterator containerControlElem) ++
      findNestedContainers(containerControlElem).iterator filter isRepeat foreach  { containerElem ⇒

      val newControlName = getControlName(containerElem)
      val bindElem       = findBindByName(ctx.formDefinitionRootElem, newControlName).get

      ensureTemplateReplaceContent(
        controlName = newControlName,
        content     = createTemplateContentFromBind(bindElem firstChildOpt * head, ctx.componentBindings))
    }

    // Update ancestor templates if any
    updateTemplatesCheckContainers(findAncestorRepeatNames(intoContainerElem, includeSelf = true).to[Set])

    // Make sure new grids and cells are annotated
    annotateGridsAndCells(newContainerElem)

    // Select first grid cell
    selectFirstCellInContainer(newContainerElem)
  }

  def pasteSingleControlFromXcv(
    xcvElem        : NodeInfo,
    insertPosition : Option[ControlPosition])(implicit
    ctx            : FormBuilderDocContext
  ): Unit = {

    val insertCellElemOpt =
      insertPosition match {
        case Some(ControlPosition(gridName, Coordinate1(x, y))) ⇒



          findControlByName(ctx.formDefinitionRootElem, gridName).toList descendant CellTest collectFirst {
            case cell if NodeInfoCellOps.x(cell).contains(x) && NodeInfoCellOps.y(cell).contains(y) ⇒ cell
          }

        case None ⇒ ensureEmptyCell()
      }

     insertCellElemOpt foreach { incertCellElem ⇒

      implicit val ctx = FormBuilderDocContext()

      val controlElem = xcvElem / XcvEntry.Control.entryName / * head

      def dataHolders = xcvElem / XcvEntry.Holder.entryName / * take 1
      def resources   = xcvElem / XcvEntry.Resources.entryName / "resource" / *

      val name = {
        val requestedName = getControlName(controlElem)

        // Check if name is already in use
        if (findInViewTryIndex(ctx.formDefinitionRootElem, controlId(requestedName)).isDefined) {
          // If so create new name
          val newName = controlNameFromId(nextId(XcvEntry.Control.entryName))

          // Rename everything
          renameControlByElement(controlElem, newName, resources / * map (_.localname) toSet)

          dataHolders ++ resources foreach
            (rename(_, newName))

          (xcvElem / XcvEntry.Bind.entryName / * headOption) foreach
            (renameBindElement(_, newName))

          newName
        } else
          requestedName
      }

      // Insert control and holders
      val newControlElem = insert(into = incertCellElem, origin = controlElem).head

      insertHolders(
        controlElement       = newControlElem,
        dataHolders          = dataHolders,
        resourceHolders      = xcvElem / XcvEntry.Resources.entryName / "resource" map (r ⇒ (r attValue "*:lang", (r / * headOption).toList)),
        precedingControlName = precedingBoundControlNameInSectionForControl(newControlElem)
      )

      // Create the bind and copy all attributes and content
      val bind = ensureBinds(findContainerNamesForModel(incertCellElem) :+ name)
      (xcvElem / XcvEntry.Bind.entryName / * headOption) foreach { xcvBind ⇒
        insert(into = bind, origin = (xcvBind /@ @*) ++ (xcvBind / *))
      }

      // Rename nested element ids and alert ids
      val nestedElemsWithId =
        for {
          nestedElem ← bind descendant *
          id         ← nestedElem.idOpt
        } yield
          nestedElem → id

      val oldIdToNewId =
        nestedElemsWithId map (_._2) zip nextTmpIds(token = Names.Validation, count = nestedElemsWithId.size) toMap

      // Update nested element ids, in particular xf:constraint/@id
      nestedElemsWithId foreach { case (nestedElem, oldId) ⇒
        setvalue(nestedElem att "id", oldIdToNewId(oldId))
      }

      val alertsWithValidationId =
        for {
          alertElem    ← newControlElem / (XF → "alert")
          validationId ← alertElem attValueOpt Names.Validation
        } yield
          alertElem → validationId

      // Update xf:alert/@validation and xf:constraint/@id
      alertsWithValidationId foreach { case (alertWithValidation, oldValidationId) ⇒

        val newValidationIdOpt = oldIdToNewId.get(oldValidationId)

        newValidationIdOpt foreach { newValidationId ⇒
          setvalue(alertWithValidation att Names.Validation, newValidationId)
        }
      }

      // This can impact templates
      updateTemplatesCheckContainers(findAncestorRepeatNames(incertCellElem).to[Set])
    }
  }

  private object Private {

    val LHHAResourceNamesToInsert = LHHANames - "alert"

    // NOTE: Help is added when needed
    val lhhaTemplate: NodeInfo =
      <template xmlns:xf="http://www.w3.org/2002/xforms">
        <xf:label ref=""/>
        <xf:hint  ref=""/>
        <xf:alert ref=""/>
      </template>

    def findGridInsertionPoint(implicit ctx: FormBuilderDocContext): (NodeInfo, Option[NodeInfo], Option[NodeInfo]) =
      findSelectedCell match {
        case Some(currentCellElem) ⇒ // A cell is selected

          val containers = findAncestorContainersLeafToRoot(currentCellElem)

          // We must always have a parent (grid) and grandparent (possibly fr:body) container
          assert(containers.size >= 2)

          val parentContainer      = containers.headOption
          val grandParentContainer = containers.tail.head

          // NOTE: At some point we could allow any grid bound and so with a name/id and bind
          //val newGridName = "grid-" + nextId(doc, "grid")

          (grandParentContainer, parentContainer, parentContainer)

        case _ ⇒ // No cell is selected, add top-level grid
          (ctx.bodyElem, childrenContainers(ctx.bodyElem) lastOption, None)
      }

    def findSectionInsertionPoint(implicit ctx: FormBuilderDocContext): (NodeInfo, Option[NodeInfo]) =
      findSelectedCell match {
        case Some(currentCellElem) ⇒ // A cell is selected

          val containers = findAncestorContainersLeafToRoot(currentCellElem)

          // We must always have a parent (grid) and grandparent (possibly fr:body) container
          assert(containers.size >= 2)

          // Idea: section is inserted after current section/tabview, NOT within current section. If there is no
          // current section/tabview, the section is inserted after the current grid.
          val grandParentContainer            = containers.tail.head // section/tab, body
          val greatGrandParentContainerOption = containers.tail.tail.headOption

          greatGrandParentContainerOption match {
            case Some(greatGrandParentContainer) ⇒ (greatGrandParentContainer, Some(grandParentContainer))
            case None                            ⇒ (grandParentContainer, grandParentContainer / * headOption)
          }

        case _ ⇒ // No cell is selected, add top-level section
          (ctx.bodyElem, childrenContainers(ctx.bodyElem) lastOption)
      }
  }
}