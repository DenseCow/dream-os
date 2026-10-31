package com.dreamos.desktop.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.dreamos.desktop.browse.BrowsePage
import com.dreamos.desktop.browse.HubPage
import com.dreamos.desktop.home.HomePage
import com.dreamos.desktop.mylist.MyListPage
import com.dreamos.desktop.search.SearchPage
import com.dreamos.desktop.shell.data.CardItem
import com.dreamos.desktop.shell.data.Catalog
import com.dreamos.desktop.shell.data.Hub
import com.dreamos.desktop.shell.data.Title
import com.dreamos.desktop.ui.kit.CardActions
import com.dreamos.desktop.ui.kit.ShellRainbow
import com.dreamos.desktop.ui.kit.rainbowFill
import androidx.compose.ui.draw.alpha
import com.dreamos.desktop.ui.kit.Icon
import com.dreamos.desktop.ui.kit.InputMode
import com.dreamos.desktop.ui.kit.LineIcon
import com.dreamos.desktop.ui.kit.LocalCardActions
import com.dreamos.desktop.ui.kit.LocalMetrics
import com.dreamos.desktop.ui.kit.LocalReducedMotion
import com.dreamos.desktop.ui.kit.MenuLayer
import com.dreamos.desktop.ui.kit.MenuOpener
import com.dreamos.desktop.ui.kit.MenuSpec
import com.dreamos.desktop.ui.kit.MenuState
import com.dreamos.desktop.ui.kit.Palette
import com.dreamos.desktop.ui.kit.Rainbow
import com.dreamos.desktop.ui.kit.RoundButton
import com.dreamos.desktop.ui.kit.Type
import com.dreamos.desktop.ui.kit.dreamClick
import com.dreamos.desktop.ui.kit.lit
import com.dreamos.desktop.ui.kit.rainbowWhile
import com.dreamos.desktop.ui.kit.ring
import com.dreamos.desktop.ui.kit.Focus
import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.dreamos.desktop.core.service.Services
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import kotlinx.coroutines.delay

/** Full screen and the like, for the window that hosts the shell (null when rendered offscreen). */
interface WindowControl {
    val fullscreen: Boolean
    fun toggleFullscreen()
}

/** A short message at the bottom of the window (a title and a sentence), gone after a few seconds. */
class Notice(val title: String, val text: String)

/** The bar's look: pages report whether content has scrolled under it. */
class BarLook { var solid by mutableStateOf(false) }
val LocalBar = staticCompositionLocalOf { BarLook() }

/** A text field has focus: arrow keys and Backspace belong to it, not to navigation. */
object TextEditing { var active by mutableStateOf(false) }

/**
 * The shell's state: which tab is showing, each tab's own stack of pages, the page models kept between visits, the
 * options menu and the notice. It is the [Nav] every page gets and the [CardActions] every card uses.
 */
class ShellState(val catalog: Catalog, val scope: CoroutineScope, start: Tab = Tab.HOME) : Nav, CardActions {
    var tab by mutableStateOf(start)
        private set
    private val stacks = Tab.entries.associateWith { mutableStateListOf<Route>(Route.TabRoot(it)) }
    val route: Route get() = stacks.getValue(tab).last()
    val depth: Int get() = stacks.getValue(tab).size
    val menu = MenuState()
    var notice by mutableStateOf<Notice?>(null)
    private val models = HashMap<String, Any>()
    /** Focus target for the start of the current page (hero Play, the search field, the first card). */
    val entry = FocusRequester()
    /** Bumped on every new availability answer, so cards read their pill again. */
    var pillVersion by mutableStateOf(0)

    override fun pill(t: Title, narrow: Boolean): com.dreamos.desktop.shell.data.Pill? {
        pillVersion // read: a new answer recomposes the cards
        return catalog.pill(t, narrow)
    }

    /** Bumped when the current tab is picked again: its page scrolls back to the top. */
    var toTop by mutableStateOf(0)

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> model(key: String, make: () -> T): T = models.getOrPut(key, make) as T

    /** A page's model if the page has made it (the launch intro asks for Home's). */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> peek(key: String): T? = models[key] as T?

    fun rows(key: String, slots: suspend () -> List<com.dreamos.desktop.shell.data.RowSlot>, hero: (suspend () -> List<com.dreamos.desktop.shell.data.HeroItem>)?) =
        model(key) { RowsModel(scope, slots, hero) }

    override fun open(route: Route) {
        if (route is Route.TabRoot) { tab(route.tab); return }
        stacks.getValue(tab).add(route)
    }

    /** The open layers' own ways back (pages over a group, Choose a Source, a PIN pad), newest last. */
    val backChain = com.dreamos.desktop.settings.kit.BackChain()
    private var backing = false

    /**
     * One level back: the newest open layer's own way back first (it calls back here again to leave its page, which
     * then pops the stack), else the stack, else Home. False only at Home's root, with nothing open.
     */
    override fun back(): Boolean {
        if (!backing) backChain.top()?.let { h ->
            backing = true
            try { h() } finally { backing = false }
            return true
        }
        val s = stacks.getValue(tab)
        return when {
            s.size > 1 -> { s.removeAt(s.lastIndex); true }
            tab != Tab.HOME -> { tab = Tab.HOME; true }
            else -> { toTop++; false }
        }
    }

    override fun tab(tab: Tab) {
        if (this.tab == tab) {
            val s = stacks.getValue(tab)
            if (s.size > 1) s.removeRange(1, s.size) else toTop++
        }
        this.tab = tab
    }

    /** Opens Dream Player (the window's player in the app; a recorder offscreen). */
    var launcher: PlayerLauncher = PlayerLauncher { _, _ -> }
    /** Settings' Run Setup Again. */
    var runSetup: () -> Unit = {}
    /** Settings' Replay Intro: the launch intro once more, over the page. */
    var replayIntro: () -> Unit = {}
    /** The avatar menu's Exit Dream OS: the window's own close (Main's quit); offscreen checks record it instead. */
    var exit: () -> Unit = {}
    /** "Exit Dream OS?" is showing ([ExitPrompt]): Esc at the very root asks it. */
    var askExit by mutableStateOf(false)

    override fun play(t: Title, from: CardItem?) = playAt(t, from?.season, from?.episode, null, fromStart = false)

    /** Plays [t] (an episode when [season] and [episode] are given; a show's next episode otherwise). */
    fun playAt(t: Title, season: Int?, episode: Int?, episodeName: String?, fromStart: Boolean) {
        if (AppServices.playback == null) {
            notice = Notice("Playback Isn't Ready", "Dream Core didn't start. Restart Dream OS and try again.")
            return
        }
        scope.launch {
            val target = Targets.of(t, season, episode, episodeName)
            launcher.play(target, if (fromStart) 1 else 0)
        }
    }

    override fun playTrailer(t: Title) = playTrailer(t, null)

    /**
     * Plays [t]'s trailer in Dream Player's trailer mode (core.trailer's TrailerPlayback): [videoKey] is TMDB's video
     * when the page knows it, else the title's details trailer key; with none, TMDB's list is read anyway.
     */
    fun playTrailer(t: Title, videoKey: String?) {
        scope.launch {
            val d = runCatching { Services.titles.details(t.kind, t.id) }.getOrNull()
            val title = d?.title ?: t
            val ref = com.dreamos.desktop.core.trailer.TrailerRef(title.id, title.isShow, title.name, hintKey = videoKey?.ifBlank { null } ?: d?.trailerKey)
            val tp = com.dreamos.desktop.core.trailer.TrailerPlayback(ref, AppServices.playback)
            val target = com.dreamos.desktop.core.trailer.TrailerPlayback.target(
                ref, title.year.toIntOrNull(), title.backdrop("w1280"), com.dreamos.desktop.core.model.TmdbImage.url(d?.logoPath, "w500"),
            )
            launcher.trailer(tp, target)
        }
    }

    /** A source picked in Choose a Source (already resolved): Dream Player plays exactly it. */
    fun playPrepared(request: com.dreamos.desktop.core.playback.PlayRequest) = launcher.prepared(request)

    /** A profile was chosen: every tab starts again from its top. */
    fun profileChanged() {
        Tab.entries.forEach { t -> stacks.getValue(t).let { s -> if (s.size > 1) s.removeRange(1, s.size) } }
        tab = Tab.HOME
        toTop++
    }

    // ---- CardActions ----

    override fun openHub(tile: com.dreamos.desktop.core.model.HubTile) = open(Route.HubPage(com.dreamos.desktop.shell.data.Hub.season(tile)))

    override fun open(item: CardItem) {
        if (item.newEpisode) return openEpisode(item)
        if (item.progress != null || item.upNext) play(item.title, item) else openTitle(item.title)
    }

    override fun episodePill(item: CardItem): com.dreamos.desktop.shell.data.Pill? {
        pillVersion // read: a new answer recomposes the card
        val s = item.season ?: return null
        val e = item.episode ?: return null
        return catalog.episodePill(item.title, s, e, "", item.airDate.orEmpty())
    }

    /**
     * A New Episodes for You card (the Fire Stick's): it's seen (its NEW badge goes); one still to come opens the show,
     * an aired one offers Play S2 E5 or Show Details.
     */
    private fun openEpisode(item: CardItem) {
        val t = item.title
        val s = item.season ?: return
        val e = item.episode ?: return
        scope.launch {
            runCatching {
                com.dreamos.desktop.core.service.Services.newEpisodes.markSeen(
                    com.dreamos.desktop.core.model.CatalogItem(t, episode = com.dreamos.desktop.core.model.Episode(t.id, s, e, "")))
            }
        }
        if (item.upcoming) return openTitle(t)
        menu.show(MenuSpec("${t.name}  ·  S${s}E$e", listOf(
            "Play S$s E$e" to { playAt(t, s, e, null, fromStart = false) },
            "Show Details" to { openTitle(t) },
        ), null))
    }

    override fun menu(item: CardItem, continueRow: Boolean) = MenuOpener { at ->
        val t = item.title
        val saved = catalog.myList.value.any { it.key == t.key }
        // New Episodes for You draws the same wide cards, but its cards aren't on Continue Watching (they have no
        // progress): removing one hid nothing and the card stayed.
        val onContinue = continueRow && (item.progress != null || item.upNext)
        menu.show(
            MenuSpec(
                t.name,
                if (onContinue) listOf(
                    (if (item.upNext || (item.progress ?: 0f) <= 0f) "Play" else "Resume") to { play(t, item) },
                    "Details" to { openTitle(t) },
                    "Remove from Continue Watching" to { catalog.removeContinue(t) },
                ) else listOf(
                    "Details" to { openTitle(t) },
                    (if (saved) "Remove from My List" else "Add to My List") to { catalog.toggleMyList(t) },
                    "Play Trailer" to { playTrailer(t) },
                ),
                at,
                removes = setOf("Remove from Continue Watching", "Remove from My List"),
            ),
        )
    }
}

/** The app inside the window: intro, bar, pages, options menu and notices, with the keyboard and mouse model. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DreamApp(state: ShellState, window: WindowControl?, intro: Boolean, onIntroDone: () -> Unit = {}) {
    val m = LocalMetrics.current
    var showIntro by remember { mutableStateOf(intro) }
    var rootOnly by remember { mutableStateOf(true) }
    val root = remember { FocusRequester() }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    // Focus rests on the root when nothing else holds it. Asked for after the frame, never from inside a focus
    // callback: re-entering the focus tree while a focused control is being removed corrupted it.
    var parkRoot by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { Focus.park = root; Focus.request(root, "shell: root") }
    LaunchedEffect(parkRoot) {
        if (parkRoot == 0) return@LaunchedEffect
        withFrameMillis { }
        if (rootOnly) Focus.request(root, "shell: root after a control went away")
    }
    // A new page: keyboard users land on its entry (hero Play, the search field) once it has settled; otherwise the
    // root keeps focus so the next key still arrives.
    LaunchedEffect(state.route) {
        delay(if (Motion.reduced) 60 else 380)
        if (InputMode.keyboard) Focus.request(state.entry, "shell: page entry") else if (rootOnly) Focus.request(root, "shell: root")
    }
    val bar = remember { BarLook() }
    LaunchedEffect(state) { state.catalog.pillUpdates.collect { state.pillVersion++ } }
    // Where focus was when Esc asked "Exit Dream OS?": Cancel puts it back there (else on the root).
    var exitReturn by remember { mutableStateOf<FocusRequester?>(null) }
    val cancelExit = {
        state.askExit = false
        if (!Focus.request(exitReturn, "exit prompt: back to the control")) Focus.request(root, "exit prompt: back to the root")
        exitReturn = null
    }
    CompositionLocalProvider(LocalNav provides state, LocalCardActions provides state, LocalBar provides bar, LocalReducedMotion provides Motion.reduced,
        com.dreamos.desktop.settings.kit.LocalBackChain provides state.backChain) {
        Box(
            Modifier.fillMaxSize().background(Palette.page)
                .onPointerEvent(PointerEventType.Move, PointerEventPass.Initial) { InputMode.keyboard = false }
                .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { e ->
                    InputMode.keyboard = false
                    // The mouse's Back button: what is open on top first (the menu, the exit question), then one level back.
                    if (e.button == PointerButton.Back) when {
                        state.menu.spec != null -> state.menu.close()
                        state.askExit -> cancelExit()
                        else -> state.back()
                    }
                }
                // The root takes focus when nothing else has it, so keys always arrive; an arrow then lands on the
                // page's entry (hero Play, the search field).
                .onFocusChanged {
                    rootOnly = it.isFocused || !it.hasFocus
                    // The focused control went away with its page: take focus back so keys keep arriving.
                    if (!it.hasFocus) parkRoot++
                }
                .focusRequester(root)
                .focusTarget()
                .onPreviewKeyEvent { k ->
                    if (k.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    InputMode.keyboard = true
                    if (state.menu.spec != null || state.askExit) return@onPreviewKeyEvent false
                    when {
                        k.key == Key.F11 || (k.key == Key.Enter && k.isAltPressed) -> { window?.toggleFullscreen(); true }
                        k.key == Key.F && k.isCtrlPressed -> { state.tab(Tab.SEARCH); true }
                        // Settings › Navigation Sounds (the Fire Stick's tick and click); the key goes on to its control.
                        (k.key == Key.Enter || k.key == Key.NumPadEnter) && !rootOnly -> { com.dreamos.desktop.notices.UiSounds.select(); false }
                        rootOnly && k.key in ARROWS -> { Focus.request(state.entry, "shell: page entry"); true }
                        // Arrows move focus to the nearest control that way (a text field keeps Left and Right).
                        k.key in ARROWS && !(TextEditing.active && (k.key == Key.DirectionLeft || k.key == Key.DirectionRight)) ->
                            com.dreamos.desktop.notices.UiSounds.tick().let { _ -> Focus.move(
                                focus,
                                when (k.key) {
                                    Key.DirectionUp -> FocusDirection.Up
                                    Key.DirectionDown -> FocusDirection.Down
                                    Key.DirectionLeft -> FocusDirection.Left
                                    else -> FocusDirection.Right
                                },
                                "shell: arrow key",
                            ) }.let { true }
                        else -> false
                    }
                }
                // Esc and Backspace step back one level, last: whatever is open below takes them first (a menu, a
                // picker, Choose a Source, a PIN pad, a settings page over its group, a setup step), and only what
                // nothing used comes here. Esc never leaves full screen by itself (F11 or Alt+Enter does; the player
                // has its own). Another tab's root goes to Home; at Home's root Esc asks "Exit Dream OS?" (owner,
                // 2026-10-30), and Cancel puts focus back where it was.
                .onKeyEvent { k ->
                    if (k.type != KeyEventType.KeyDown || state.menu.spec != null || state.askExit) return@onKeyEvent false
                    when {
                        k.key == Key.Escape -> {
                            if (!state.back() && !showIntro) { exitReturn = if (rootOnly) null else Focus.last; state.askExit = true }
                            true
                        }
                        k.key == Key.Backspace && !TextEditing.active -> { state.back(); true }
                        else -> false
                    }
                },
        ) {
            val route = state.route
            AnimatedContent(
                targetState = route,
                transitionSpec = { pageTransition(Motion.reduced, m.u(14)) },
                label = "page",
                contentKey = { routeKey(it) },
            ) { r -> Page(r, state) }

            // The bar belongs to the tabs and their hubs; win-pages' pages are full screen with their own Back.
            if (route is Route.TabRoot || route is Route.HubPage) TopBar(state, bar.solid)
            Notices(state)
            MenuLayer(state.menu)
            ExitPrompt(state, cancelExit)
            if (showIntro) Intro(Motion.reduced) { showIntro = false; onIntroDone() }
        }
    }
}

private val ARROWS = setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)

private fun pageTransition(reduced: Boolean, rise: Dp): ContentTransform =
    if (reduced) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
    else (fadeIn(tween(240, delayMillis = 60)) + slideInVertically(tween(300)) { (rise.value * 2).toInt() }) togetherWith fadeOut(tween(140))

fun routeKey(r: Route): String = when (r) {
    is Route.TabRoot -> "tab:${r.tab}"
    is Route.HubPage -> "hub:${r.hub}"
    is Route.Details -> "title:${r.title.key}"
    is Route.PersonPage -> "person:${r.person.id}"
    is Route.Settings -> "settings:${r.group}:${r.route}"
    Route.Profiles -> "profiles"
    Route.Notices -> "notices"
}

@Composable
private fun Page(r: Route, state: ShellState) {
    when (r) {
        is Route.TabRoot -> when (r.tab) {
            Tab.HOME -> HomePage(state)
            Tab.BROWSE -> BrowsePage(state)
            Tab.SEARCH -> SearchPage(state)
            Tab.MYLIST -> MyListPage(state)
        }
        is Route.HubPage -> HubPage(state, r.hub)
        else -> PagesPage(r, state)
    }
}

/**
 * The top bar: Back (when a page is open over a tab), the Dream OS cloud, the four tabs, then Settings and the
 * profile. Transparent over a hero with a soft veil for legibility; smoked glass with a hairline once content scrolls
 * under it (the one glass surface in the app). It stays put on a PC: the mouse needs it within reach.
 */
@Composable
private fun TopBar(state: ShellState, solid: Boolean) {
    val m = LocalMetrics.current
    val glassA by animateFloatAsState(if (solid) 1f else 0f, tween(220))
    Box(Modifier.fillMaxWidth().height(m.barH)) {
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent))))
        Box(Modifier.matchParentSize().background(Palette.glass.copy(alpha = Palette.glass.alpha * glassA)))
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(m.u(1)).background(Palette.hairline.copy(alpha = Palette.hairline.alpha * glassA)))
        Row(Modifier.fillMaxSize().padding(horizontal = m.gutter - m.u(6)), verticalAlignment = Alignment.CenterVertically) {
            AnimatedVisibility(state.depth > 1) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(Icon.LEFT, "Back", { state.back() }, size = 38f)
                    Spacer(Modifier.width(m.u(14)))
                }
            }
            Logo { state.tab(Tab.HOME) }
            Spacer(Modifier.width(m.u(26)))
            Row(horizontalArrangement = Arrangement.spacedBy(m.u(4)), verticalAlignment = Alignment.CenterVertically) {
                for (t in Tab.entries) TabButton(t, current = state.tab == t) { state.tab(t) }
            }
            Spacer(Modifier.weight(1f))
            Bell { state.open(Route.Notices) }
            Spacer(Modifier.width(m.u(12)))
            Avatar(state)
        }
    }
}

@Composable
private fun Logo(onClick: () -> Unit) {
    val m = LocalMetrics.current
    val src = remember { MutableInteractionSource() }
    val on = src.lit()
    val ringA by animateFloatAsState(if (on) 1f else 0f, tween(180))
    val hue by rainbowWhile(ringA > 0f)
    Box(
        Modifier.semantics { contentDescription = "Dream OS Home" }
            .ring(ringA, hue, m.u(8), m)
            .clip(RoundedCornerShape(m.u(8)))
            .dreamClick(src, onClick)
            .padding(horizontal = m.u(6), vertical = m.u(4)),
    ) {
        Image(painterResource("dream_mark.png"), null, Modifier.height(m.u(34)))
    }
}

/**
 * A tab: its line icon and label (Search is its icon alone, as on the Fire Stick). No bubble: the current or lit tab
 * is its bright, bold label with the rainbow line centered under the label.
 */
@Composable
private fun TabButton(t: Tab, current: Boolean, onClick: () -> Unit) {
    val m = LocalMetrics.current
    val src = remember { MutableInteractionSource() }
    val on = src.lit()
    val c by animateColorAsState(if (on || current) Palette.text else Color(0xA8FFFFFF), tween(160))
    val lineA by animateFloatAsState(if (on || current) 1f else 0f, tween(200))
    val icon = when (t) { Tab.SEARCH -> Icon.SEARCH; Tab.HOME -> Icon.HOME; Tab.BROWSE -> Icon.FILM; Tab.MYLIST -> Icon.BOOKMARK }
    Column(
        Modifier.semantics { contentDescription = t.label }
            .clip(RoundedCornerShape(m.u(8)))
            .dreamClick(src, onClick)
            .padding(horizontal = m.u(10), vertical = m.u(6)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(m.u(30))) {
            LineIcon(icon, c, Modifier.size(m.u(if (t == Tab.SEARCH) 19 else 17)))
            if (t != Tab.SEARCH) {
                Spacer(Modifier.width(m.u(9)))
                Text(t.label, style = Type.tab().copy(color = c, fontWeight = if (on || current) FontWeight.SemiBold else FontWeight.Medium), maxLines = 1)
            }
        }
        Spacer(Modifier.height(m.u(4)))
        // The line: the fading rainbow under the current page's tab and the one being pointed at or focused.
        Box(
            Modifier.size(m.u(20), m.u(3)).alpha(lineA).then(if (lineA > 0f) Modifier.rainbowFill(RoundedCornerShape(50)) else Modifier),
        )
    }
}

/** Notifications: the bell, with the unread count on a small disc in the fading rainbow at its top right. */
@Composable
private fun Bell(onClick: () -> Unit) {
    val m = LocalMetrics.current
    val list by Services.notices.notices.collectAsState()
    val unread = list.count { !it.read }
    Box {
        RoundButton(Icon.BELL, if (unread > 0) "Notifications, $unread Unread" else "Notifications", onClick, size = 40f)
        if (unread > 0) Box(
            Modifier.align(Alignment.TopEnd).offset(x = m.u(5), y = (-m.u(4)))
                .height(m.u(17)).widthIn(min = m.u(17)).clip(RoundedCornerShape(50)).rainbowFill()
                .border(m.u(1.5f), Palette.page, RoundedCornerShape(50)).padding(horizontal = m.u(4)),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (unread > 9) "9+" else "$unread", style = Type.badge().copy(color = ShellRainbow.inkNow.value), maxLines = 1)
        }
    }
}

/**
 * The current profile: its picture (emoji) in a disc of its color. A click opens its menu: Switch Profile, Settings,
 * Notifications and, under a line, Exit Dream OS (the PC's Close Dream OS; no confirmation, it's an explicit pick).
 */
internal const val EXIT = "Exit Dream OS"

@Composable
private fun Avatar(state: ShellState) {
    val m = LocalMetrics.current
    val src = remember { MutableInteractionSource() }
    val on = src.lit()
    val ringA by animateFloatAsState(if (on) 1f else 0f, tween(180))
    val hue by rainbowWhile(ringA > 0f)
    val me by Services.profiles.current.collectAsState()
    val profile: Pair<String, String?> = me.name to me.emoji
    val menuW = with(androidx.compose.ui.platform.LocalDensity.current) { m.u(300).toPx() }
    val tint = com.dreamos.desktop.core.model.Profile.COLORS.getOrNull(me.color)?.let { Color(it) } ?: Color(0xFF26262B)
    var at by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val self = remember { FocusRequester() }
    val onClick = {
        // Esc or a click outside puts focus back here.
        com.dreamos.desktop.ui.kit.MenuReturn.to = self
        state.menu.show(MenuSpec(me.name, listOf(
            "Switch Profile" to { state.open(Route.Profiles) },
            "Settings" to { state.open(Route.Settings()) },
            "Notifications" to { state.open(Route.Notices) },
            EXIT to { state.exit() },
        ), at, breakBefore = setOf(EXIT)))
    }
    Box(
        Modifier.semantics { contentDescription = "Profile, ${profile.first}" }
            .onGloballyPositioned { c -> val b = c.boundsInWindow(); at = androidx.compose.ui.geometry.Offset(b.right - menuW, b.bottom + 8f) }
            .focusRequester(self)
            .ring(ringA, hue, m.u(20), m)
            .size(m.u(40)).clip(CircleShape)
            .background(Brush.linearGradient(listOf(com.dreamos.desktop.ui.kit.mix(tint, Color(0xFF151517), 0.55f), Color(0xFF151517))))
            .border(m.u(1), Color(0x33FFFFFF), CircleShape)
            .dreamClick(src, onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            !profile.second.isNullOrBlank() -> Text(profile.second!!, style = Type.style(19f, FontWeight.Normal))
            profile.first.isNotBlank() -> Text(profile.first.take(1).uppercase(), style = Type.style(17f, FontWeight.SemiBold))
            else -> LineIcon(Icon.PERSON, Palette.text, Modifier.size(m.u(20)))
        }
    }
}

@Composable
private fun Notices(state: ShellState) {
    val m = LocalMetrics.current
    val n = state.notice
    LaunchedEffect(n) { if (n != null) { delay(4_500); if (state.notice === n) state.notice = null } }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(n != null, enter = fadeIn(tween(180)), exit = fadeOut(tween(260))) {
            val shown = remember(n) { n } ?: return@AnimatedVisibility
            Column(
                Modifier.padding(bottom = m.u(36)).widthIn(max = m.u(560))
                    .clip(RoundedCornerShape(m.u(14))).background(Color(0xF2141416))
                    .border(m.u(1), Palette.hairline, RoundedCornerShape(m.u(14)))
                    .padding(horizontal = m.u(22), vertical = m.u(16)),
            ) {
                Text(shown.title, style = Type.style(16f, FontWeight.SemiBold))
                Spacer(Modifier.height(m.u(4)))
                Text(shown.text, style = Type.body())
            }
        }
    }
}

/** A page with no hero under the bar: the bar is glass from the start. */
@Composable
fun BarSolid() {
    val bar = LocalBar.current
    androidx.compose.runtime.SideEffect { bar.solid = true }
}

/** Opens a hub page (Browse tiles, Home's seasonal row). */
fun Nav.openHub(hub: Hub) = open(Route.HubPage(hub))
