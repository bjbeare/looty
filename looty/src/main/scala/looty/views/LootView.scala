package looty
package views

import org.scalajs.jquery.{JQuery, JQueryStatic}
import scala.scalajs.js
import looty.model.{InventoryId, StashTabId, ContainerId, ComputedItemProps, PoeCacher, ComputedItem}
import scala.collection.immutable
import cgta.ojs.lang.{JsFuture, JsObjectBuilder}
import scala.concurrent.Future
import looty.model.parsers.ItemParser


//////////////////////////////////////////////////////////////
// Copyright (c) 2013 Ben Jackman, Jeff Gomberg
// All Rights Reserved
// please contact ben@jackman.biz or jeff@cgtanalytics.com
// for licensing inquiries
// Created by bjackman @ 12/9/13 11:17 PM
//////////////////////////////////////////////////////////////

object LootFilter {
  def all = LootFilter("All", _ => true)
}

case class LootFilter(text: String, p: ComputedItem => Boolean) {
  def allows(i: ComputedItem): Boolean = {
    try p(i) catch {case e: Throwable => false}
  }
}


class LootView(val league: String) extends View {
  val obj        = new JsObjectBuilder
  var containers = immutable.Map.empty[ContainerId, List[ComputedItem]]
  var buttons    = immutable.Map.empty[ContainerId, JQuery]


  val jq: JQueryStatic = global.jQuery.asInstanceOf[JQueryStatic]

  var grid    : js.Dynamic = null
  var dataView: js.Dynamic = js.Dynamic.newInstance(global.Slick.Data.DataView)()
  var tabFilter            = LootFilter.all
  dataView.setIdGetter { (d: ComputedItem) => d.item.locationId.get}


  def start(el: JQuery) {
    console.log("Starting Grid View")
    setHtml(el).foreach(x => addAllItems)
  }

  def addAllItems {
    val items = new js.Array[ComputedItem]()
    val pc = new PoeCacher()

    for {
      cons <- pc.getAllContainersFuture(league)
      conFut <- cons
      (conId, con) <- conFut
    } {
      updateContainer(conId, con)
    }
  }


  def updateContainer(containerId: ContainerId, container: List[ComputedItem]) {
    dataView.beginUpdate()
    for {
      container <- containers.get(containerId)
      item <- container
      locId <- item.item.locationId.toOption
    } {
      dataView.deleteItem(locId)
    }
    containers += containerId -> container
    //    buttons.get(containerId).foreach(_.css("border-color", ""))
    buttons.get(containerId) match {
      case Some(btn) => btn.css("border-color", "")
      case None => console.error("No button for container", containerId)
    }
    for {
      item <- container
    } {
      dataView.addItem(item.asInstanceOf[js.Any])
    }
    dataView.endUpdate()
  }


  def stop() {}

  private def setHtml(el: JQuery): Future[Unit] = {
    el.empty()
    el.append("""<div id="controls"></div>""")
    el.append("""<div id="grid"></div>""")
    el.append("""<div id="itemdetail" style="z-index:100;color:white;background-color:black;opacity:.9;display:none;position:fixed;left:50px;top:100px">SAMPLE DATA<br>a<br>a<br>a<br>a<br>a<br>a<br>a<br>a<br>a</div>""")
    appendGrid()
    appendControls(jq("#controls"))
  }

  private def appendControls(el: JQuery): Future[Unit] = {
    val pc = new PoeCacher()

    el.empty()
    val showHide = jq("""<a href="javascript:void(0)">[ show/hide controls ]<a>""")
    el.append(showHide)
    var shown = true
    val subControls = jq("<div></div>")
    el.append(subControls)
    showHide.click { () =>
      if (shown) {
        shown = false
        subControls.hide()
      } else {
        shown = true
        subControls.show()
      }
      false
    }

    val elClear = jq("<div></div>")
    subControls.append(elClear)

    val clearBtn = jq("""<button title="Use this button after you move or rename premium stash tabs, or
    |have changed several tabs. This will take some time, as GGG throttles the number of requests made per minute
    |to get this data.">Clear And Reload Everything For This League From Server</button>""".stripMargin)
    elClear.append(clearBtn)
    clearBtn.click { () =>
      pc.Store.clearLeague(league).foreach { x =>
        global.location.reload()
      }
    }

    val elTabs = jq("<div></div>")
    val elChars = jq("<div></div>")

    subControls.append(elTabs)
    subControls.append(elChars)

    //Buttons for stashed
    val tabBtnsFut = for {
      stis <- pc.Net.getStisAndStore(league)
    } yield {
      stis.foreach { sti =>
        val button = jq(s"""<button>${sti.n}</button>""")
        button.css(obj[js.Any](
          color = "white",
          textShadow = "-1px 0 black, 0 1px black, 1px 0 black, 0 -1px black",
          backgroundColor = sti.colour.toRgb
        ))
        val conId: ContainerId = StashTabId((sti.i: Double).toInt)
        buttons += conId -> button
        if (!containers.contains(conId)) button.css("border-color", "red")
        elTabs.append(button)
        button.on("click", (a: js.Any) => {
          //Refresh this tab
          pc.Net.getStashTabAndStore(league, sti.i.toInt).foreach { st =>
            val items = for (item <- st.allItems(None)) yield ItemParser.parseItem(item, conId, sti.n)
            updateContainer(conId, items)
          }
          //Filter the grid to show only that tab
          tabFilter = LootFilter("Only One Tab", _.containerId =?= conId)
        })
      }
    }

    //Buttons for characters
    val charBtnsFut = for {
      chars <- pc.Net.getCharsAndStore
    } yield {
      chars.foreach { char =>
        if (char.league.toString =?= league) {
          val button = jq(s"""<button>${char.name}</button>""")
          button.css(obj[js.Any](
            borderColor = "red"
          ))
          button.data("charName", char.name)
          val conId: ContainerId = InventoryId(char.name)
          buttons += conId -> button
          if (!containers.contains(conId)) button.css("border-color", "red")
          elChars.append(button)
          button.on("click", (a: js.Any) => {
            //Refresh this tab
            pc.Net.getInvAndStore(char.name).foreach { st =>
              val items = for (item <- st.allItems(None)) yield ItemParser.parseItem(item, conId, char.name)
              updateContainer(conId, items)
            }
            //Filter the grid to show only that tab
            tabFilter = LootFilter("Only One Tab", _.containerId =?= conId)
          })
        }
      }
    }

    JsFuture.sequence(List(tabBtnsFut, charBtnsFut)).map(x => Unit)
  }

  //  private def showAnyItems(xs: List[AnyItem], location: String) {
  //    val items = new js.Array[ComputedItem]()
  //    for {
  //      item <- xs
  //    } {
  //      val ci = ItemParser.parseItem(item)
  //      ci.location = location
  //      items.push(ci)
  //    }
  //    showComputedItems(items)
  //  }

  //  private def showComputedItems(xs: js.Array[ComputedItem]) {
  //    console.log("SETTING ITEMS", xs)
  //    dataView.setItems(xs)
  //    grid.invalidate()
  //    grid.render()
  //  }

  private def appendGrid() {
    def makeColumn(name: String, tooltip: String, width : Int)(f: ComputedItem => js.Any) = {
      val o = newObject
      o.id = name
      o.name = name
      o.field = name
      o.toolTip = tooltip
      o.sortable = true
      o.getter = f
      if (width =?= -1) o.width = 60 else o.width = width
      o
    }
    val columns = js.Array[js.Dynamic]()
    var columnFilters = Map.empty[String, LootFilter]

    ComputedItemProps.all.foreach { p =>
      val col = makeColumn(p.shortName, p.description, p.width)(p.getJs)
      columns.push(col)
    }

    def setFilter(colId: String, text: String) {
      val col = columns.find(_.id == colId.toJs).get
      if (text.trim.isEmpty) {
        columnFilters -= colId
      } else {
        def numFilter(n: String)(p: (Double, Double) => Boolean) = {
          val x = n.toDouble
          LootFilter(text, i => p(col.getter(i.asInstanceOf[js.Any]).toString.toDouble, x))
        }
        val GT = ">(.*)".r
        val GTE = ">=(.*)".r
        val LT = "<(.*)".r
        val LTE = "<=(.*)".r
        val EQ = "=(.*)".r

        val fil = try {
          text.trim match {
            case GTE(n) if n.nonEmpty => numFilter(n)(_ >= _)
            case GT(n) if n.nonEmpty => numFilter(n)(_ > _)
            case LTE(n) if n.nonEmpty => numFilter(n)(_ <= _)
            case LT(n) if n.nonEmpty => numFilter(n)(_ < _)
            case EQ(n) if n.nonEmpty => numFilter(n)(_ == _)
            case "" => LootFilter(text, i => true)
            case s =>
              val toks = s.split(" ")
              LootFilter(text, (i) => {
                val value = col.getter(i.asInstanceOf[js.Any]).toString.toLowerCase
                toks.exists(tok => value.matches(".*" + tok.toLowerCase + ".*"))
              })
          }
        } catch {
          case e: Throwable =>
            LootFilter(text, i => true)
        }
        columnFilters += colId -> fil
      }
    }

    val options = {
      val o = newObject
      o.enableCellNavigation = true
      o.enableColumnReorder = false
      o.multiColumnSort = true
      o.showHeaderRow = true
      o.headerRowHeight = 30
      o.explicitInitialization = true
      o.dataItemColumnValueExtractor = (item: ComputedItem, column: js.Dynamic) => {
        column.getter(item.asInstanceOf[js.Any])
      }
      o
    }

    def filter(item: ComputedItem): js.Boolean = {
      columnFilters.forall { case (colId, fil) =>
        fil.allows(item)
      } && tabFilter.allows(item)
    }


    grid = js.Dynamic.newInstance(global.Slick.Grid)("#grid", dataView, columns, options)

    dataView.onRowCountChanged.subscribe(() => {
      grid.updateRowCount()
      grid.render()
    })

    dataView.onRowsChanged.subscribe((e: js.Dynamic, args: js.Dynamic) => {
      grid.invalidateRows(args.rows)
      grid.render()
    })

    dataView.setFilter(filter _)

    grid.onHeaderRowCellRendered.subscribe((e: js.Dynamic, args: js.Dynamic) => {
      jq(args.node).empty()
      val el = jq("<input type='text'>")
          .data("columnId", args.column.id)

      columnFilters.get(args.column.id.asJsStr).foreach { fil =>
        el.`val`(fil.text)
      }

      el.on("keyup", () => {
        setFilter(args.column.id.toString, el.`val`().toString)
        dataView.refresh()
      })
      el.appendTo(args.node)
    })

    addSort()
    addMouseover()
    //addFiltering()

    def resize() {
      jq("#grid").css("height", global.window.innerHeight - 120)
      grid.resizeCanvas()
    }

    jq(global.window).resize(() => resize())
    resize()

    grid.init()

  }

  private def addSort() {
    grid.onSort.subscribe((e: js.Dynamic, args: js.Dynamic) => {
      val cols = args.sortCols.asInstanceOf[js.Array[js.Dynamic]]

      dataView.sort {
        (a: ComputedItem, b: ComputedItem) =>
          var i = 0
          var ret = 0.0
          while (i < cols.length && ret == 0) {
            val col = cols(i)
            val sign = if (cols(i).sortAsc.asInstanceOf[js.Boolean]) 1 else -1
            val a1: js.Dynamic = col.sortCol.getter(a.asInstanceOf[js.Any])
            val b1: js.Dynamic = col.sortCol.getter(b.asInstanceOf[js.Any])

            val res = a1 - b1
            if (js.isNaN(res)) {
              if (a1.toString > b1.toString) {
                ret = sign
              } else if (b1.toString > a1.toString) {
                ret = -sign
              }

            } else {
              ret = sign * res
            }

            i += 1
          }
          ret: js.Number
      }

      grid.invalidate()
      grid.render()
    })
  }

  private def addMouseover() {
    grid.onMouseEnter.subscribe((e: js.Dynamic, args: js.Any) => {
      val row = grid.getCellFromEvent(e).row
      if (row.nullSafe.isDefined) {
        val (top, bottom) = if (e.clientY / global.window.innerHeight < .5) {
          Some(e.clientY.asJsNum + 10) -> None
        } else {
          None -> Some(global.window.innerHeight - e.clientY + 10)
        }

        val (right, left) = if (e.clientX / global.window.innerWidth < .5) {
          None -> Some(e.clientX.asJsNum + 10)
        } else {
          Some(global.window.innerWidth - e.clientX + 10) -> None
        }

        val item = grid.getDataItem(row).asInstanceOf[ComputedItem]
        showItemDetail(top, right, bottom, left, item)
      }
    })
    grid.onMouseLeave.subscribe((e: js.Dynamic, args: js.Any) => {
      jq("#itemdetail").hide()
    })
  }


  def showItemDetail(
    top: Option[js.Number],
    right: Option[js.Number],
    bottom: Option[js.Number],
    left: Option[js.Number],
    item: ComputedItem) {

    val d = jq("#itemdetail")
    d.show()
    d.css("top", top.getOrElse("".toJs))
    d.css("right", right.getOrElse("".toJs))
    d.css("bottom", bottom.getOrElse("".toJs))
    d.css("left", left.getOrElse("".toJs))
    val color = item.item.getFrameType.color
    def requirements = {
      val xs = for {
        rs <- item.item.requirements.toOption.toList
        r <- rs.toList
        n <- r.name.toOption.toList
        vs <- r.values.toList
      } yield {
        s"$n ${vs(0).toString}"
      }
      xs.oIf(_.nonEmpty, _ => xs.mkString("Requires ", ", ", ""), _ => "")
    }
    def properties = {
      (for {
        props <- item.item.properties.toOption.toList
        prop <- props.toList
      } yield {
        val vs = for {
          v <- prop.values.toList
        } yield {
          v(0)
        }
        prop.name + " " + vs.mkString("")
      }).mkString("<br>")
    }
    def flavorText = {
      item.item.flavourText.toOption.map(_.toList.mkString("<br>")).getOrElse("")
    }
    val sections = List(
      item.item.name.toString,
      item.item.typeLine.toString,
      s"Location: ${item.locAndCoords}",
      "Sockets: " + item.socketColors,
      properties,
      requirements,
      item.item.descrText.toOption.map(_.toString).getOrElse(""),
      item.item.implicitModList.mkString("<br>"),
      item.item.explicitModList.mkString("<br>"),
      item.item.secDescrText.toOption.map(_.toString).getOrElse(""),
      item.item.cosmeticMods.toOption.map(_.mkString("<br>")).getOrElse(""),
      flavorText,
      if (item.item.identified.toOption.map(x => x: Boolean).getOrElse(true)) "" else "Not Identified",
      if (item.item.corrupted.toOption.map(x => x: Boolean).getOrElse(false)) "Corrupted" else "",
      if (item.item.duplicated.toOption.map(x => x: Boolean).getOrElse(false)) "Mirrored" else ""
    ).filter(_.nonEmpty)
    val h = s"""
    <div style="color:$color;padding:5px">
    ${sections.mkString("<hr>")}
    </div>
    """

    d.html(h)

    console.log(item)
  }
}

