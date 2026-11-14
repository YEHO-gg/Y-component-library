(function () {
  "use strict";

  /* =========================================================
     Helpers
     ========================================================= */

  const $ = (selector, root) => (root || document).querySelector(selector);
  const $$ = (selector, root) => Array.prototype.slice.call((root || document).querySelectorAll(selector));
  const pad = (value) => String(value).padStart(2, "0");
  const clamp = (value, min, max) => Math.min(max, Math.max(min, value));

  const BASE_TITLE = document.title;
  const body = document.body;
  const root = document.documentElement;

  const timeNow = () => new Date();

  /* =========================================================
     Theme
     ========================================================= */

  const themeMeta = $("#theme-color");
  const themeToggle = $("#theme-toggle");

  function syncThemeUI() {
    const isLight = root.dataset.theme === "light";
    if (themeMeta) {
      themeMeta.setAttribute("content", isLight ? "#f2eee5" : "#0c0d0f");
    }
    if (themeToggle) {
      themeToggle.setAttribute("aria-label", isLight ? "切换到深色主题" : "切换到浅色主题");
      themeToggle.title = isLight ? "切换到深色主题（T）" : "切换到浅色主题（T）";
    }
  }

  function setTheme(theme, persist) {
    root.dataset.theme = theme === "light" ? "light" : "dark";
    if (persist !== false) {
      try {
        localStorage.setItem("chronos-theme", root.dataset.theme);
      } catch (error) {
        /* Storage can be unavailable on file URLs. The theme still works. */
      }
    }
    syncThemeUI();
  }

  syncThemeUI();

  if (themeToggle) {
    themeToggle.addEventListener("click", () => {
      setTheme(root.dataset.theme === "light" ? "dark" : "light");
    });
  }

  /* =========================================================
     Local time zone and place names
     ========================================================= */

  let localZone = "UTC";
  try {
    localZone = Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
  } catch (error) {
    localZone = "UTC";
  }

  const PLACES = {
    "Asia/Shanghai": { city: "上海", country: "中国", en: "Shanghai" },
    "Asia/Chongqing": { city: "重庆", country: "中国", en: "Chongqing" },
    "Asia/Harbin": { city: "哈尔滨", country: "中国", en: "Harbin" },
    "Asia/Urumqi": { city: "乌鲁木齐", country: "中国", en: "Urumqi" },
    "Asia/Hong_Kong": { city: "香港", country: "中国", en: "Hong Kong" },
    "Asia/Macau": { city: "澳门", country: "中国", en: "Macau" },
    "Asia/Taipei": { city: "台北", country: "中国台湾", en: "Taipei" },
    "Asia/Tokyo": { city: "东京", country: "日本", en: "Tokyo" },
    "Asia/Seoul": { city: "首尔", country: "韩国", en: "Seoul" },
    "Asia/Singapore": { city: "新加坡", country: "新加坡", en: "Singapore" },
    "Asia/Dubai": { city: "迪拜", country: "阿联酋", en: "Dubai" },
    "Asia/Kolkata": { city: "新德里", country: "印度", en: "New Delhi" },
    "Asia/Bangkok": { city: "曼谷", country: "泰国", en: "Bangkok" },
    "Asia/Jakarta": { city: "雅加达", country: "印度尼西亚", en: "Jakarta" },
    "Europe/London": { city: "伦敦", country: "英国", en: "London" },
    "Europe/Paris": { city: "巴黎", country: "法国", en: "Paris" },
    "Europe/Berlin": { city: "柏林", country: "德国", en: "Berlin" },
    "Europe/Madrid": { city: "马德里", country: "西班牙", en: "Madrid" },
    "Europe/Rome": { city: "罗马", country: "意大利", en: "Rome" },
    "Europe/Moscow": { city: "莫斯科", country: "俄罗斯", en: "Moscow" },
    "America/New_York": { city: "纽约", country: "美国", en: "New York" },
    "America/Chicago": { city: "芝加哥", country: "美国", en: "Chicago" },
    "America/Denver": { city: "丹佛", country: "美国", en: "Denver" },
    "America/Los_Angeles": { city: "洛杉矶", country: "美国", en: "Los Angeles" },
    "America/Vancouver": { city: "温哥华", country: "加拿大", en: "Vancouver" },
    "America/Toronto": { city: "多伦多", country: "加拿大", en: "Toronto" },
    "America/Sao_Paulo": { city: "圣保罗", country: "巴西", en: "São Paulo" },
    "America/Mexico_City": { city: "墨西哥城", country: "墨西哥", en: "Mexico City" },
    "Australia/Sydney": { city: "悉尼", country: "澳大利亚", en: "Sydney" },
    "Australia/Melbourne": { city: "墨尔本", country: "澳大利亚", en: "Melbourne" },
    "Australia/Perth": { city: "珀斯", country: "澳大利亚", en: "Perth" },
    "Pacific/Auckland": { city: "奥克兰", country: "新西兰", en: "Auckland" },
    "Africa/Cairo": { city: "开罗", country: "埃及", en: "Cairo" },
    "Africa/Johannesburg": { city: "约翰内斯堡", country: "南非", en: "Johannesburg" },
    UTC: { city: "协调世界时", country: "", en: "Coordinated Universal Time" },
    "Etc/UTC": { city: "协调世界时", country: "", en: "Coordinated Universal Time" }
  };

  function placeForZone(zone) {
    if (PLACES[zone]) {
      return Object.assign({ zone: zone }, PLACES[zone]);
    }
    const fallback = zone.split("/").pop().replace(/_/g, " ");
    return { zone: zone, city: fallback, country: "", en: fallback };
  }

  const localPlace = placeForZone(localZone);

  /* =========================================================
     Date / time formatting
     ========================================================= */

  const dateFormatter = new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "long",
    day: "numeric"
  });

  const weekdayFormatter = new Intl.DateTimeFormat("zh-CN", {
    weekday: "long"
  });

  const lunarFormatter = (() => {
    try {
      return new Intl.DateTimeFormat("zh-CN-u-ca-chinese", {
        year: "numeric",
        month: "long",
        day: "numeric"
      });
    } catch (error) {
      return null;
    }
  })();

  const LUNAR_DAYS = [
    "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
    "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
    "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十"
  ];

  function lunarDate(date) {
    if (!lunarFormatter) return "";
    try {
      const parts = lunarFormatter.formatToParts(date);
      const pick = (type) => {
        const part = parts.find((item) => item.type === type);
        return part ? part.value : "";
      };
      const yearName = pick("yearName");
      const month = pick("month");
      const dayNumber = parseInt(pick("day"), 10);
      const dayName = Number.isFinite(dayNumber) ? LUNAR_DAYS[dayNumber - 1] || pick("day") : pick("day");
      return "农历 " + (yearName ? yearName + "年 " : "") + month + dayName;
    } catch (error) {
      return "";
    }
  }

  function utcOffsetLabel(date) {
    const minutes = -date.getTimezoneOffset();
    const sign = minutes >= 0 ? "+" : "−";
    const absolute = Math.abs(minutes);
    return "UTC" + sign + pad(Math.floor(absolute / 60)) + ":" + pad(absolute % 60);
  }

  function dayOfYear(date) {
    const start = Date.UTC(date.getFullYear(), 0, 1);
    const today = Date.UTC(date.getFullYear(), date.getMonth(), date.getDate());
    return Math.floor((today - start) / 86400000) + 1;
  }

  function isoWeek(date) {
    const target = new Date(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()));
    const dayNumber = target.getUTCDay() || 7;
    target.setUTCDate(target.getUTCDate() + 4 - dayNumber);
    const yearStart = new Date(Date.UTC(target.getUTCFullYear(), 0, 1));
    return Math.ceil(((target - yearStart) / 86400000 + 1) / 7);
  }

  function weekProgress(date) {
    const mondayOffset = (date.getDay() + 6) % 7;
    const monday = new Date(date.getFullYear(), date.getMonth(), date.getDate() - mondayOffset);
    monday.setHours(0, 0, 0, 0);
    return clamp(((date.getTime() - monday.getTime()) / (7 * 86400000)) * 100, 0, 100);
  }

  /* =========================================================
     View switching
     ========================================================= */

  const tabs = $$(".tab");
  const views = $$(".view");
  const viewNames = views.map((view) => view.dataset.view);
  const tabRail = $(".tab-rail");

  function activateView(name, options) {
    const settings = options || {};
    if (viewNames.indexOf(name) === -1) name = "clock";

    body.dataset.view = name;

    tabs.forEach((tab) => {
      const isActive = tab.dataset.target === name;
      tab.classList.toggle("active", isActive);
      tab.setAttribute("aria-selected", String(isActive));
      tab.tabIndex = isActive ? 0 : -1;
    });

    views.forEach((view) => {
      const isActive = view.dataset.view === name;
      view.classList.toggle("active", isActive);
      view.hidden = !isActive;
    });

    if (name !== "clock" && body.classList.contains("focus-mode")) {
      setFocus(false);
    }

    if (settings.updateHash !== false) {
      try {
        history.replaceState(null, "", "#" + name);
      } catch (error) {
        /* Ignore URL restrictions. */
      }
    }

    if (settings.focusTab) {
      const tab = tabs.find((item) => item.dataset.target === name);
      if (tab) tab.focus();
    }
  }

  tabs.forEach((tab) => {
    tab.addEventListener("click", () => activateView(tab.dataset.target));
  });

  if (tabRail) {
    tabRail.addEventListener("keydown", (event) => {
      const currentIndex = tabs.indexOf(document.activeElement);
      if (currentIndex === -1) return;
      let nextIndex = null;

      if (event.key === "ArrowRight") nextIndex = (currentIndex + 1) % tabs.length;
      if (event.key === "ArrowLeft") nextIndex = (currentIndex - 1 + tabs.length) % tabs.length;
      if (event.key === "Home") nextIndex = 0;
      if (event.key === "End") nextIndex = tabs.length - 1;

      if (nextIndex !== null) {
        event.preventDefault();
        activateView(tabs[nextIndex].dataset.target, { focusTab: true });
      }
    });
  }

  const brand = $("#brand");
  if (brand) {
    brand.addEventListener("click", (event) => {
      event.preventDefault();
      activateView("clock");
    });
  }

  const initialHash = (location.hash || "").replace("#", "");
  activateView(viewNames.indexOf(initialHash) !== -1 ? initialHash : "clock", { updateHash: false });

  window.addEventListener("hashchange", () => {
    const name = (location.hash || "").replace("#", "");
    if (viewNames.indexOf(name) !== -1) {
      activateView(name, { updateHash: false });
    }
  });

  /* =========================================================
     Focus view
     ========================================================= */

  const focusToggle = $("#focus-toggle");
  const focusExit = $("#focus-exit");

  function setFocus(enabled) {
    if (enabled && body.dataset.view !== "clock") {
      activateView("clock");
    }
    body.classList.toggle("focus-mode", enabled);
    if (focusToggle) {
      focusToggle.setAttribute("aria-pressed", String(enabled));
      focusToggle.setAttribute("aria-label", enabled ? "退出专注视图" : "进入专注视图");
    }
  }

  if (focusToggle) {
    focusToggle.addEventListener("click", () => {
      setFocus(!body.classList.contains("focus-mode"));
    });
  }

  if (focusExit) {
    focusExit.addEventListener("click", () => setFocus(false));
  }

  /* =========================================================
     Toast
     ========================================================= */

  const toast = $("#toast");
  let toastTimer = 0;

  function showToast(message) {
    if (!toast) return;
    toast.textContent = message;
    toast.classList.add("show");
    window.clearTimeout(toastTimer);
    toastTimer = window.setTimeout(() => {
      toast.classList.remove("show");
    }, 2600);
  }

  /* =========================================================
     Local clock
     ========================================================= */

  const timeHour = $("#time-hour");
  const timeMinute = $("#time-minute");
  const timeSecond = $("#time-second");
  const heroDate = $("#hero-date");
  const heroLunar = $("#hero-lunar");
  const heroUtc = $("#hero-utc");
  const localCity = $("#local-city");
  const localZoneLabel = $("#local-zone");
  const statusTime = $("#status-time");
  const dayPercent = $("#day-percent");
  const dayFill = $("#day-fill");
  const dayNow = $("#day-now");
  const statZone = $("#stat-zone");
  const statZoneHint = $("#stat-zone-hint");
  const statElapsed = $("#stat-elapsed");
  const statElapsedHint = $("#stat-elapsed-hint");
  const statWeek = $("#stat-week");
  const statWeekHint = $("#stat-week-hint");
  const statYear = $("#stat-year");
  const statYearHint = $("#stat-year-hint");
  const analogZone = $("#analog-zone");
  const footerYear = $("#footer-year");
  const handHour = $("#hand-hour");
  const handMinute = $("#hand-minute");
  const handSecond = $("#hand-second");

  localCity.textContent = localPlace.country ? localPlace.city + " · " + localPlace.country : localPlace.city;
  localZoneLabel.textContent = localZone;
  analogZone.textContent = localZone.toUpperCase();
  if (footerYear) footerYear.textContent = String(timeNow().getFullYear());

  let lastRenderedDayKey = "";
  let lastSecondValue = -1;

  function renderLocalClock(now) {
    const hours = now.getHours();
    const minutes = now.getMinutes();
    const seconds = now.getSeconds();

    timeHour.textContent = pad(hours);
    timeMinute.textContent = pad(minutes);
    timeSecond.textContent = pad(seconds);

    if (seconds !== lastSecondValue) {
      timeSecond.classList.remove("tick");
      void timeSecond.offsetWidth;
      timeSecond.classList.add("tick");
      lastSecondValue = seconds;
    }

    statusTime.textContent = pad(hours) + ":" + pad(minutes);

    const dayKey = now.getFullYear() + "-" + now.getMonth() + "-" + now.getDate();
    if (dayKey !== lastRenderedDayKey) {
      lastRenderedDayKey = dayKey;
      heroDate.textContent = dateFormatter.format(now) + " " + weekdayFormatter.format(now);
      const lunar = lunarDate(now);
      heroLunar.textContent = lunar || "农历";
      statsForDate(now);
    }

    heroUtc.textContent = utcOffsetLabel(now);
    statZoneHint.textContent = utcOffsetLabel(now) + " · " + localPlace.city;

    const secondsToday = hours * 3600 + minutes * 60 + seconds + now.getMilliseconds() / 1000;
    const progress = (secondsToday / 86400) * 100;
    dayPercent.textContent = progress.toFixed(1) + "%";
    dayFill.style.width = progress.toFixed(3) + "%";
    dayNow.style.left = progress.toFixed(3) + "%";

    statElapsed.textContent = hours > 0 ? hours + " 小时 " + minutes + " 分" : minutes + " 分";
    statElapsedHint.textContent = "今日进度 " + progress.toFixed(1) + "%";

    const weekPercent = weekProgress(now);
    statWeekHint.textContent = "周一至今 " + weekPercent.toFixed(0) + "%";
  }

  function statsForDate(now) {
    statZone.textContent = localZone;
    statWeek.textContent = "第 " + isoWeek(now) + " 周";

    const year = now.getFullYear();
    const dayNumber = dayOfYear(now);
    const nextYear = Date.UTC(year + 1, 0, 1);
    const todayUtc = Date.UTC(year, now.getMonth(), now.getDate());
    const remaining = Math.max(0, Math.ceil((nextYear - todayUtc) / 86400000));
    statYear.textContent = remaining + " 天";
    statYearHint.textContent = year + " 年 · 第 " + dayNumber + " 天";
  }

  function buildClockTicks() {
    const tickGroup = $("#clock-ticks");
    if (!tickGroup) return;
    const fragment = document.createDocumentFragment();
    const center = 160;

    for (let index = 0; index < 60; index += 1) {
      const isMajor = index % 5 === 0;
      const angle = ((index * 6) - 90) * (Math.PI / 180);
      const outerRadius = 145;
      const innerRadius = isMajor ? 131 : 138;
      const line = document.createElementNS("http://www.w3.org/2000/svg", "line");
      line.setAttribute("x1", String(center + Math.cos(angle) * innerRadius));
      line.setAttribute("y1", String(center + Math.sin(angle) * innerRadius));
      line.setAttribute("x2", String(center + Math.cos(angle) * outerRadius));
      line.setAttribute("y2", String(center + Math.sin(angle) * outerRadius));
      line.setAttribute("class", isMajor ? "tick tick-major" : "tick");
      line.setAttribute("stroke-width", isMajor ? "1.7" : "0.8");
      line.setAttribute("opacity", isMajor ? "0.78" : "0.34");
      fragment.appendChild(line);
    }

    tickGroup.appendChild(fragment);
  }

  function animateClockHands() {
    if (!document.hidden && body.dataset.view === "clock") {
      const now = new Date();
      const seconds = now.getSeconds() + now.getMilliseconds() / 1000;
      const minutes = now.getMinutes() + seconds / 60;
      const hours = (now.getHours() % 12) + minutes / 60;

      handSecond.setAttribute("transform", "rotate(" + (seconds * 6).toFixed(2) + " 160 160)");
      handMinute.setAttribute("transform", "rotate(" + (minutes * 6).toFixed(2) + " 160 160)");
      handHour.setAttribute("transform", "rotate(" + (hours * 30).toFixed(2) + " 160 160)");
    }
    window.requestAnimationFrame(animateClockHands);
  }

  buildClockTicks();
  renderLocalClock(timeNow());
  statsForDate(timeNow());
  window.requestAnimationFrame(animateClockHands);

  /* =========================================================
     World clocks
     ========================================================= */

  const worldGrid = $("#world-grid");
  const worldCount = $("#world-count");

  const WORLD_CITIES = [
    { zone: "America/New_York", city: "纽约", en: "New York" },
    { zone: "Europe/London", city: "伦敦", en: "London" },
    { zone: "Europe/Paris", city: "巴黎", en: "Paris" },
    { zone: "Asia/Dubai", city: "迪拜", en: "Dubai" },
    { zone: "Asia/Tokyo", city: "东京", en: "Tokyo" },
    { zone: "Australia/Sydney", city: "悉尼", en: "Sydney" },
    { zone: "America/Los_Angeles", city: "洛杉矶", en: "Los Angeles" },
    { zone: "Asia/Singapore", city: "新加坡", en: "Singapore" }
  ];

  const worldTimeFormatters = new Map();
  const worldDateFormatters = new Map();
  const worldOffsetFormatters = new Map();
  const worldCards = [];
  let lastOffsetRefresh = 0;

  function timeFormatterFor(zone) {
    if (!worldTimeFormatters.has(zone)) {
      worldTimeFormatters.set(zone, new Intl.DateTimeFormat("en-GB", {
        timeZone: zone,
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
        hourCycle: "h23"
      }));
    }
    return worldTimeFormatters.get(zone);
  }

  function dateFormatterFor(zone) {
    if (!worldDateFormatters.has(zone)) {
      worldDateFormatters.set(zone, new Intl.DateTimeFormat("zh-CN", {
        timeZone: zone,
        month: "long",
        day: "numeric",
        weekday: "short"
      }));
    }
    return worldDateFormatters.get(zone);
  }

  function offsetFormatterFor(zone) {
    if (!worldOffsetFormatters.has(zone)) {
      worldOffsetFormatters.set(zone, new Intl.DateTimeFormat("en-US", {
        timeZone: zone,
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
        hourCycle: "h23"
      }));
    }
    return worldOffsetFormatters.get(zone);
  }

  function partsMap(formatter, date) {
    const map = {};
    formatter.formatToParts(date).forEach((part) => {
      if (part.type !== "literal") map[part.type] = part.value;
    });
    return map;
  }

  function zoneOffsetMinutes(zone, date) {
    try {
      const parts = partsMap(offsetFormatterFor(zone), date);
      const asUtc = Date.UTC(
        Number(parts.year),
        Number(parts.month) - 1,
        Number(parts.day),
        Number(parts.hour),
        Number(parts.minute),
        Number(parts.second)
      );
      return Math.round((asUtc - Math.floor(date.getTime() / 1000) * 1000) / 60000);
    } catch (error) {
      return 0;
    }
  }

  function relativeOffsetLabel(difference) {
    if (difference === 0) return "与本地同步";
    const sign = difference > 0 ? "+" : "−";
    const absolute = Math.abs(difference);
    const hours = Math.floor(absolute / 60);
    const minutes = absolute % 60;
    if (hours === 0) return sign + minutes + " 分钟";
    return sign + hours + " 小时" + (minutes ? " " + minutes + " 分" : "");
  }

  function phaseMarkup(isNight) {
    if (isNight) {
      return '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M19.3 14.8A7.7 7.7 0 0 1 9.2 4.7 8 8 0 1 0 19.3 14.8Z"/></svg>';
    }
    return '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="4"/><path d="M12 2.8v2M12 19.2v2M2.8 12h2M19.2 12h2M5.5 5.5l1.4 1.4M17.1 17.1l1.4 1.4M18.5 5.5l-1.4 1.4M6.9 17.1l-1.4 1.4"/></svg>';
  }

  function createWorldCard(city, isLocal) {
    const card = document.createElement("article");
    card.className = "world-card" + (isLocal ? " is-local" : "");
    card.dataset.zone = city.zone;
    card.setAttribute("aria-label", city.city + "时间");

    card.innerHTML =
      '<div class="world-card-top">' +
        '<div class="world-city">' +
          '<div class="city-name"><span>' + city.city + '</span>' +
            (isLocal ? '<span class="city-local-tag">本地</span>' : "") +
          '</div>' +
          '<span class="city-en">' + city.en + '</span>' +
        '</div>' +
        '<span class="world-phase" data-phase><span>白天</span></span>' +
      '</div>' +
      '<div class="world-time" data-world-time>' +
        '<span data-hour>00</span><span class="world-colon">:</span>' +
        '<span data-minute>00</span><span class="world-colon">:</span>' +
        '<span class="world-sec" data-second>00</span>' +
      '</div>' +
      '<div class="world-meta">' +
        '<span data-world-date>--</span>' +
        '<span class="world-offset" data-world-offset>--</span>' +
      '</div>' +
      '<div class="world-bar" aria-hidden="true"><span data-world-bar></span></div>';

    worldGrid.appendChild(card);

    return {
      zone: city.zone,
      isLocal: isLocal,
      card: card,
      phase: $("[data-phase]", card),
      hour: $("[data-hour]", card),
      minute: $("[data-minute]", card),
      second: $("[data-second]", card),
      date: $("[data-world-date]", card),
      offset: $("[data-world-offset]", card),
      bar: $("[data-world-bar]", card),
      time: $("[data-world-time]", card),
      lastPhase: "",
      lastDate: "",
      lastOffset: 0
    };
  }

  if (worldGrid) {
    const worldList = [Object.assign({}, localPlace, { zone: localZone })].concat(
      WORLD_CITIES
        .filter((city) => city.zone !== localZone)
        .map((city) => Object.assign({}, placeForZone(city.zone), { city: city.city, en: city.en }))
    );

    worldList.forEach((city, index) => {
      worldCards.push(createWorldCard(city, index === 0));
    });

    worldCount.textContent = worldCards.length + " 个城市";
  }

  function updateWorldClocks(now) {
    if (!worldCards.length) return;

    const refreshOffsets = now.getTime() - lastOffsetRefresh > 30000;
    const localOffset = -now.getTimezoneOffset();

    worldCards.forEach((item) => {
      let timeParts;
      try {
        timeParts = partsMap(timeFormatterFor(item.zone), now);
      } catch (error) {
        return;
      }

      const hours = Number(timeParts.hour);
      const minutes = Number(timeParts.minute);
      const seconds = Number(timeParts.second);
      const isNight = hours < 6 || hours >= 18;

      item.hour.textContent = pad(hours);
      item.minute.textContent = pad(minutes);
      item.second.textContent = pad(seconds);
      item.time.setAttribute("aria-label", pad(hours) + ":" + pad(minutes) + ":" + pad(seconds));

      const phase = isNight ? "night" : "day";
      if (phase !== item.lastPhase) {
        item.lastPhase = phase;
        item.card.classList.toggle("is-night", isNight);
        item.phase.className = "world-phase " + (isNight ? "is-night" : "is-day");
        item.phase.innerHTML = phaseMarkup(isNight) + "<span>" + (isNight ? "夜晚" : "白天") + "</span>";
      }

      let dateParts;
      try {
        dateParts = partsMap(dateFormatterFor(item.zone), now);
      } catch (error) {
        dateParts = null;
      }
      if (dateParts) {
        const monthText = /月$/.test(dateParts.month) ? dateParts.month : dateParts.month + "月";
        const dateText = monthText + dateParts.day + "日 " + dateParts.weekday;
        if (dateText !== item.lastDate) {
          item.lastDate = dateText;
          item.date.textContent = dateText;
        }
      }

      if (refreshOffsets) {
        const difference = zoneOffsetMinutes(item.zone, now) - localOffset;
        item.lastOffset = difference;
        item.offset.textContent = item.isLocal ? "本地时间" : relativeOffsetLabel(difference);
      }

      item.bar.style.width = (((hours * 3600 + minutes * 60 + seconds) / 86400) * 100).toFixed(2) + "%";
    });

    if (refreshOffsets) lastOffsetRefresh = now.getTime();
  }

  /* =========================================================
     Main second tick
     ========================================================= */

  let lastClockSecond = -1;

  function tick() {
    const now = timeNow();
    if (now.getSeconds() === lastClockSecond) return;
    lastClockSecond = now.getSeconds();
    renderLocalClock(now);
    updateWorldClocks(now);
  }

  tick();
  window.setInterval(tick, 180);

  /* =========================================================
     Stopwatch
     ========================================================= */

  const swStatus = $("#sw-status");
  const swMain = $("#sw-main");
  const swCentiseconds = $("#sw-cs");
  const swToggle = $("#sw-toggle");
  const swLapButton = $("#sw-lap");
  const swReset = $("#sw-reset");
  const swCard = $(".stopwatch-card");
  const lapList = $("#lap-list");
  const lapCount = $("#lap-count");
  const lapColumns = $("#lap-columns");

  const stopwatch = {
    running: false,
    elapsed: 0,
    startedAt: 0,
    laps: [],
    lastLapTotal: 0,
    frame: 0
  };

  const LAPS_EMPTY_MARKUP =
    '<li class="laps-empty">' +
      '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="13" r="7.25"/><path d="M12 13V9.5M9.5 3.25h5M12 3.25v2.5"/></svg>' +
      '<p>还没有计次记录</p>' +
      '<span>开始计时后，按下「计次」记录分段时间。</span>' +
    '</li>';

  function stopwatchElapsed() {
    return stopwatch.running ? stopwatch.elapsed + (performance.now() - stopwatch.startedAt) : stopwatch.elapsed;
  }

  function formatLapTime(milliseconds) {
    const total = Math.max(0, milliseconds);
    const minutes = Math.floor(total / 60000);
    const seconds = Math.floor(total / 1000) % 60;
    const centiseconds = Math.floor(total / 10) % 100;
    return pad(minutes) + ":" + pad(seconds) + "." + pad(centiseconds);
  }

  function setStopwatchStatus(label, running) {
    swStatus.classList.toggle("running", running);
    swStatus.innerHTML = '<span class="tool-status-dot" aria-hidden="true"></span>' + label;
    if (swCard) swCard.classList.toggle("is-running", running);
  }

  function renderStopwatch() {
    const total = stopwatchElapsed();
    const minutes = Math.floor(total / 60000);
    const seconds = Math.floor(total / 1000) % 60;
    const centiseconds = Math.floor(total / 10) % 100;

    swMain.textContent = pad(minutes) + ":" + pad(seconds);
    swCentiseconds.textContent = "." + pad(centiseconds);
    swToggle.textContent = stopwatch.running ? "暂停" : (total > 0 ? "继续" : "开始");
    swLapButton.disabled = !stopwatch.running;
  }

  function renderLaps() {
    lapCount.textContent = stopwatch.laps.length + " LAPS";
    lapColumns.hidden = stopwatch.laps.length === 0;

    if (!stopwatch.laps.length) {
      lapList.innerHTML = LAPS_EMPTY_MARKUP;
      return;
    }

    const splits = stopwatch.laps.map((lap) => lap.split);
    const fastest = splits.length > 1 ? Math.min.apply(null, splits) : null;

    lapList.innerHTML = stopwatch.laps
      .slice()
      .reverse()
      .map((lap, reverseIndex) => {
        const lapNumber = stopwatch.laps.length - reverseIndex;
        const badge = fastest !== null && lap.split === fastest
          ? '<span class="lap-badge fastest">最快</span>'
          : "";
        return (
          '<li class="lap-item">' +
            '<span class="lap-index">' + pad(lapNumber) + '</span>' +
            '<span class="lap-split">' + formatLapTime(lap.split) + badge + '</span>' +
            '<span class="lap-total">' + formatLapTime(lap.total) + '</span>' +
          '</li>'
        );
      })
      .join("");
  }

  function stopwatchLoop() {
    if (!stopwatch.running) return;
    renderStopwatch();
    stopwatch.frame = window.requestAnimationFrame(stopwatchLoop);
  }

  function startStopwatch() {
    if (stopwatch.running) return;
    stopwatch.startedAt = performance.now();
    stopwatch.running = true;
    setStopwatchStatus("计时中", true);
    renderStopwatch();
    window.cancelAnimationFrame(stopwatch.frame);
    stopwatchLoop();
  }

  function pauseStopwatch() {
    if (!stopwatch.running) return;
    stopwatch.elapsed = stopwatchElapsed();
    stopwatch.running = false;
    window.cancelAnimationFrame(stopwatch.frame);
    setStopwatchStatus("已暂停", false);
    renderStopwatch();
  }

  function resetStopwatch() {
    stopwatch.running = false;
    stopwatch.elapsed = 0;
    stopwatch.startedAt = 0;
    stopwatch.laps = [];
    stopwatch.lastLapTotal = 0;
    window.cancelAnimationFrame(stopwatch.frame);
    setStopwatchStatus("就绪", false);
    renderStopwatch();
    renderLaps();
  }

  function recordLap() {
    if (!stopwatch.running) return;
    const total = stopwatchElapsed();
    const split = total - stopwatch.lastLapTotal;
    stopwatch.lastLapTotal = total;
    stopwatch.laps.push({ total: total, split: split });
    renderLaps();
    lapList.scrollTop = 0;
  }

  if (swToggle) {
    swToggle.addEventListener("click", () => {
      if (stopwatch.running) {
        pauseStopwatch();
      } else {
        startStopwatch();
      }
    });
  }

  if (swLapButton) swLapButton.addEventListener("click", recordLap);
  if (swReset) swReset.addEventListener("click", resetStopwatch);

  renderStopwatch();
  renderLaps();

  /* =========================================================
     Countdown timer
     ========================================================= */

  const TIMER_MAX = 6 * 60 * 60 * 1000;
  const TIMER_DEFAULT = 5 * 60 * 1000;

  const timerCard = $("#timer-card");
  const timerDisplay = $("#timer-display");
  const timerState = $("#timer-state");
  const timerSub = $("#timer-sub");
  const timerProgress = $("#timer-progress");
  const timerToggle = $("#timer-toggle");
  const timerReset = $("#timer-reset");
  const timerPlus = $("#timer-plus");
  const timerMinus = $("#timer-minus");
  const timerPresetLabel = $("#timer-preset-label");
  const presetButtons = $$(".preset");
  const customMin = $("#custom-min");
  const customSec = $("#custom-sec");
  const applyCustom = $("#apply-custom");

  const timer = {
    duration: TIMER_DEFAULT,
    remaining: TIMER_DEFAULT,
    running: false,
    endAt: 0,
    completed: false,
    interval: 0,
    audioContext: null
  };

  function formatTimer(milliseconds) {
    const totalSeconds = Math.max(0, Math.ceil(milliseconds / 1000));
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;
    return pad(minutes) + ":" + pad(seconds);
  }

  function formatDurationWords(milliseconds) {
    const totalSeconds = Math.round(milliseconds / 1000);
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;
    if (minutes && seconds) return minutes + " 分 " + seconds + " 秒";
    if (minutes) return minutes + " 分钟";
    return seconds + " 秒";
  }

  function setTimerTitle(value) {
    if (document.title !== value) document.title = value;
  }

  function syncTimerInputs() {
    if (!customMin || !customSec) return;
    customMin.value = String(Math.floor(timer.duration / 60000));
    customSec.value = String(Math.floor((timer.duration % 60000) / 1000));
  }

  function updatePresetSelection() {
    let matchedMinutes = null;
    presetButtons.forEach((button) => {
      const minutes = Number(button.dataset.minutes);
      const isActive = minutes * 60000 === timer.duration;
      button.classList.toggle("active", isActive);
      if (isActive) matchedMinutes = minutes;
    });
    timerPresetLabel.textContent = matchedMinutes === null ? "自定义" : matchedMinutes + " 分钟";
  }

  function updateTimerUI() {
    const display = formatTimer(timer.remaining);
    if (timerDisplay.textContent !== display) timerDisplay.textContent = display;

    const ratio = timer.duration > 0 ? clamp(timer.remaining / timer.duration, 0, 1) : 0;
    timerProgress.style.strokeDashoffset = String((100 * (1 - ratio)).toFixed(3));

    if (timer.completed) {
      timerState.textContent = "时间到";
    } else if (timer.running) {
      timerState.textContent = "倒数中";
    } else if (timer.remaining < timer.duration) {
      timerState.textContent = "已暂停";
    } else {
      timerState.textContent = "准备开始";
    }

    const isPaused = !timer.running && !timer.completed && timer.remaining < timer.duration;
    timerToggle.textContent = timer.completed ? "再来一次" : (timer.running ? "暂停" : (isPaused ? "继续" : "开始"));
    timerSub.textContent = formatDurationWords(timer.duration) + " · 专注";

    timerCard.classList.toggle("is-running", timer.running);
    timerCard.classList.toggle("is-complete", timer.completed);
    timerPlus.disabled = timer.running;
    timerMinus.disabled = timer.running;

    if (timer.completed) {
      setTimerTitle("时间到 · CHRONOS");
    } else if (timer.running) {
      setTimerTitle(display + " · 倒计时 — CHRONOS");
    } else if (isPaused) {
      setTimerTitle(display + " · 已暂停 — CHRONOS");
    } else {
      setTimerTitle(BASE_TITLE);
    }
  }

  function clearTimerInterval() {
    if (timer.interval) {
      window.clearInterval(timer.interval);
      timer.interval = 0;
    }
  }

  function setTimerDuration(milliseconds) {
    clearTimerInterval();
    timer.duration = clamp(Math.round(milliseconds / 1000) * 1000, 1000, TIMER_MAX);
    timer.remaining = timer.duration;
    timer.running = false;
    timer.completed = false;
    timer.endAt = 0;
    syncTimerInputs();
    updatePresetSelection();
    updateTimerUI();
  }

  function primeAudio() {
    try {
      const AudioContextClass = window.AudioContext || window.webkitAudioContext;
      if (!AudioContextClass) return;
      if (!timer.audioContext) timer.audioContext = new AudioContextClass();
      if (timer.audioContext.state === "suspended") {
        timer.audioContext.resume().catch(() => {});
      }
    } catch (error) {
      /* Audio is an enhancement. */
    }
  }

  function playChime() {
    try {
      primeAudio();
      const context = timer.audioContext;
      if (!context) return;
      const start = context.currentTime + 0.03;
      const notes = [880, 1108.73, 1318.51, 1760];

      notes.forEach((frequency, index) => {
        const noteStart = start + index * 0.21;
        const oscillator = context.createOscillator();
        const gain = context.createGain();
        oscillator.type = "sine";
        oscillator.frequency.setValueAtTime(frequency, noteStart);
        gain.gain.setValueAtTime(0.0001, noteStart);
        gain.gain.exponentialRampToValueAtTime(0.14, noteStart + 0.025);
        gain.gain.exponentialRampToValueAtTime(0.0001, noteStart + 0.72);
        oscillator.connect(gain);
        gain.connect(context.destination);
        oscillator.start(noteStart);
        oscillator.stop(noteStart + 0.78);
      });
    } catch (error) {
      /* Ignore unsupported audio environments. */
    }
  }

  function finishTimer() {
    clearTimerInterval();
    timer.running = false;
    timer.completed = true;
    timer.remaining = 0;
    timer.endAt = 0;
    updateTimerUI();
    playChime();
    showToast("时间到，休息一下。");
  }

  function runTimer() {
    clearTimerInterval();
    timer.interval = window.setInterval(() => {
      if (!timer.running) return;
      const remaining = Math.max(0, timer.endAt - Date.now());
      timer.remaining = remaining;
      if (remaining <= 0) {
        finishTimer();
      } else {
        updateTimerUI();
      }
    }, 100);
  }

  function startTimer() {
    primeAudio();
    if (timer.completed || timer.remaining <= 0) {
      timer.remaining = timer.duration;
      timer.completed = false;
    }
    timer.endAt = Date.now() + timer.remaining;
    timer.running = true;
    updateTimerUI();
    runTimer();
  }

  function pauseTimer() {
    if (!timer.running) return;
    timer.remaining = Math.max(0, timer.endAt - Date.now());
    timer.running = false;
    clearTimerInterval();
    updateTimerUI();
  }

  function resetTimer() {
    clearTimerInterval();
    timer.running = false;
    timer.completed = false;
    timer.remaining = timer.duration;
    timer.endAt = 0;
    updateTimerUI();
  }

  function toggleTimer() {
    if (timer.running) {
      pauseTimer();
    } else {
      startTimer();
    }
  }

  function applyCustomDuration() {
    const minutes = clamp(parseInt(customMin.value, 10) || 0, 0, 99);
    const seconds = clamp(parseInt(customSec.value, 10) || 0, 0, 59);
    const total = (minutes * 60 + seconds) * 1000;
    if (total <= 0) {
      showToast("请输入大于 0 的时长");
      return;
    }
    setTimerDuration(total);
    showToast("已设定 " + formatDurationWords(total) + " 倒计时");
  }

  presetButtons.forEach((button) => {
    button.addEventListener("click", () => {
      setTimerDuration(Number(button.dataset.minutes) * 60000);
    });
  });

  if (timerToggle) timerToggle.addEventListener("click", toggleTimer);
  if (timerReset) timerReset.addEventListener("click", resetTimer);

  if (timerPlus) {
    timerPlus.addEventListener("click", () => {
      if (timer.running) return;
      setTimerDuration(clamp(timer.duration + 60000, 60000, TIMER_MAX));
    });
  }

  if (timerMinus) {
    timerMinus.addEventListener("click", () => {
      if (timer.running) return;
      setTimerDuration(clamp(timer.duration - 60000, 60000, TIMER_MAX));
    });
  }

  if (applyCustom) applyCustom.addEventListener("click", applyCustomDuration);
  if (customMin) {
    customMin.addEventListener("keydown", (event) => {
      if (event.key === "Enter") applyCustomDuration();
    });
  }
  if (customSec) {
    customSec.addEventListener("keydown", (event) => {
      if (event.key === "Enter") applyCustomDuration();
    });
  }

  setTimerDuration(TIMER_DEFAULT);

  /* =========================================================
     Keyboard shortcuts
     ========================================================= */

  function isTypingTarget(target) {
    return target instanceof Element && Boolean(target.closest("input, textarea, select, [contenteditable='true']"));
  }

  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && body.classList.contains("focus-mode")) {
      setFocus(false);
      return;
    }

    if (event.metaKey || event.ctrlKey || event.altKey || isTypingTarget(event.target)) return;

    const key = event.key;
    const lower = key.toLowerCase();
    const activeView = body.dataset.view;

    if (key === "1" || key === "2" || key === "3" || key === "4") {
      const target = ["clock", "world", "stopwatch", "timer"][Number(key) - 1];
      activateView(target);
      return;
    }

    if (lower === "t") {
      setTheme(root.dataset.theme === "light" ? "dark" : "light");
      return;
    }

    if (lower === "f") {
      setFocus(!body.classList.contains("focus-mode"));
      return;
    }

    if (key === " " || key === "Spacebar") {
      if (event.target instanceof Element && event.target.closest("button, a")) return;
      if (activeView === "stopwatch") {
        event.preventDefault();
        if (stopwatch.running) pauseStopwatch();
        else startStopwatch();
      } else if (activeView === "timer") {
        event.preventDefault();
        toggleTimer();
      }
      return;
    }

    if (lower === "l" && activeView === "stopwatch" && stopwatch.running) {
      event.preventDefault();
      recordLap();
    }
  });
})();
