// ═══════════ إعدادات قابلة للتعديل ═══════════
// غيّر هذا إلى false لعرض الأرقام اللاتينية (0-9) بدلاً من الأرقام العربية (٠-٩)
var USE_ARABIC_NUMERALS = true;
var BG_INTERVAL_MS = 5 * 60 * 1000;     // تبديل صورة الخلفية كل 5 دقائق
var BG_FADE_MS = 2500;                  // مدة التلاشي بين الصورتين (نفس transition في style.css)
var MATCH_ROTATE_MS = 10 * 1000;        // تبديل المباراة المعروضة كل 10 ثوانٍ
var MATCH_FADE_MS = 350;                // مدة تلاشي نص المباراة (نفس transition في style.css)

// ═══════════ إعدادات تأتي من ApiConfig.kt (لا تعدّلها هنا) ═══════════
// القيم التالية افتراضية فقط (تظهر في preview.html). في التطبيق يدفع كود Kotlin القيم الحقيقية
// من ApiConfig.kt عبر setConfig() فور تحميل الصفحة: اسم المدينة، اسم الفريق، المنطقة الزمنية.
var CITY_NAME = 'المدينة المنورة';
var TEAM_NAME = 'تشيلسي';
var TZ_NAME = 'Asia/Riyadh';              // اسم المنطقة الزمنية (IANA)؛ يتعامل مع التوقيت الصيفي تلقائيًا
var TZ_FALLBACK_MS = 3 * 60 * 60 * 1000; // إزاحة احتياطية تُستخدم فقط إن لم يدعم الـ WebView أسماء المناطق

// ═══════════ أدوات مشتركة ═══════════
var ARABIC_DIGITS = ['٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩'];
var WEEKDAYS = ['الأحد', 'الاثنين', 'الثلاثاء', 'الأربعاء', 'الخميس', 'الجمعة', 'السبت'];
var MONTHS = [
  'يناير', 'فبراير', 'مارس', 'أبريل', 'مايو', 'يونيو',
  'يوليو', 'أغسطس', 'سبتمبر', 'أكتوبر', 'نوفمبر', 'ديسمبر'
];
var HIJRI_MONTHS = [
  'محرم', 'صفر', 'ربيع الأول', 'ربيع الآخر', 'جمادى الأولى', 'جمادى الآخرة',
  'رجب', 'شعبان', 'رمضان', 'شوال', 'ذو القعدة', 'ذو الحجة'
];

function $(id) { return document.getElementById(id); }

function toLocaleDigits(input) {
  var str = String(input);
  if (!USE_ARABIC_NUMERALS) return str;
  return str.replace(/[0-9]/g, function (d) { return ARABIC_DIGITS[parseInt(d, 10)]; });
}

function pad2(n) { return n < 10 ? '0' + n : '' + n; }

function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
  });
}

// يكتب النص في العنصر فقط إن تغيّر فعلًا (لا إعادة رسم بلا داعٍ — أخف على التلفزيون)
var lastText = {};
function setText(id, text) {
  if (lastText[id] === text) return;
  lastText[id] = text;
  $(id).textContent = text;
}

// نفس الفكرة لكن لسطر فيه فواصل "·" (تُرسم في span له هامش حتى لا تلتصق بالأرقام)
// ملاحظة: المدخلات هنا نصوص من الكود نفسه (أرقام وأسماء عربية) — ما يأتي من الـ API يمر عبر esc().
var lastHtml = {};
function setDotted(id, parts) {
  var html = parts.join('<span class="sep">·</span>');
  if (lastHtml[id] === html) return;
  lastHtml[id] = html;
  $(id).innerHTML = html;
}

// الوقت الحالي (epoch ms). دالة منفصلة عمدًا ليمكن استبدالها في preview.html لمحاكاة أوقات أخرى.
function nowMs() { return Date.now(); }

// إزاحة توقيت المدينة (بالميلي ثانية عن UTC) عند اللحظة ms، مع احتساب التوقيت الصيفي إن وُجد.
// نستخدم Intl بالمنطقة الزمنية المحددة؛ وإن لم يدعمها الـ WebView نرجع للإزاحة الاحتياطية.
var tzFmt = null;
function tzOffsetAt(ms) {
  if (!TZ_NAME) return TZ_FALLBACK_MS;
  try {
    if (!tzFmt) {
      tzFmt = new Intl.DateTimeFormat('en-US', {
        timeZone: TZ_NAME, hour12: false,
        year: 'numeric', month: 'numeric', day: 'numeric',
        hour: 'numeric', minute: 'numeric', second: 'numeric'
      });
    }
    var parts = tzFmt.formatToParts(new Date(ms));
    var v = {};
    for (var i = 0; i < parts.length; i++) v[parts[i].type] = parseInt(parts[i].value, 10);
    if (!v.year || !v.month || !v.day) throw new Error('bad tz parts');
    // بعض المحركات تكتب منتصف الليل "24" بدل "0"
    var asUtc = Date.UTC(v.year, v.month - 1, v.day, (v.hour || 0) % 24, v.minute || 0, v.second || 0);
    return asUtc - Math.floor(ms / 1000) * 1000;
  } catch (e) {
    TZ_NAME = ''; // لا نكرر محاولة فاشلة كل ثانية
    return TZ_FALLBACK_MS;
  }
}

// مكوّنات التاريخ والوقت بتوقيت المدينة مهما كان توقيت الجهاز
function cityParts(ms) {
  var d = new Date(ms + tzOffsetAt(ms));
  return {
    y: d.getUTCFullYear(), mo: d.getUTCMonth(), d: d.getUTCDate(), wd: d.getUTCDay(),
    h: d.getUTCHours(), mi: d.getUTCMinutes()
  };
}

// رقم اليوم بتوقيت المدينة (للمقارنة بين الأيام: اليوم / بكرة / عدد الأيام المتبقية)
function dayNumber(ms) { return Math.floor((ms + tzOffsetAt(ms)) / 86400000); }

// "٤:٢١ ص" — نظام 12 ساعة بدون صفر بادئ
function fmtTime12(ms) {
  var p = cityParts(ms);
  return toLocaleDigits(p.h % 12 || 12) + ':' + toLocaleDigits(pad2(p.mi)) + ' ' + (p.h >= 12 ? 'م' : 'ص');
}

// مفرد / مثنى / 3-10 جمع / 11+ مفرد — مثل: ساعة، ساعتين، ٣ ساعات، ١١ ساعة
function unitText(n, one, two, few, many) {
  if (n === 1) return one;
  if (n === 2) return two;
  return toLocaleDigits(n) + ' ' + (n >= 3 && n <= 10 ? few : many);
}

// التاريخ الهجري (أم القرى) من محرك Intl المدمج في الـ WebView؛ إن لم يتوفر نُخفي الهجري بدل عرض تاريخ خاطئ
function hijriLabel(ms) {
  try {
    var fmt = new Intl.DateTimeFormat('en-u-ca-islamic-umalqura-nu-latn', {
      day: 'numeric', month: 'numeric', year: 'numeric', timeZone: 'UTC'
    });
    var parts = fmt.formatToParts(new Date(ms + tzOffsetAt(ms)));
    var day = 0, month = 0, year = 0;
    for (var i = 0; i < parts.length; i++) {
      if (parts[i].type === 'day') day = parseInt(parts[i].value, 10);
      else if (parts[i].type === 'month') month = parseInt(parts[i].value, 10);
      else if (parts[i].type === 'year') year = parseInt(parts[i].value, 10);
    }
    if (!day || !year || month < 1 || month > 12) return '';
    return toLocaleDigits(day) + ' ' + HIJRI_MONTHS[month - 1] + ' ' + toLocaleDigits(year) + ' هـ';
  } catch (e) {
    return '';
  }
}

// ═══════════ الساعة والتاريخ ═══════════
var lastDayNumber = null;

function refreshDate(now) {
  var p = cityParts(now);
  setText('weekday', WEEKDAYS[p.wd]);
  var greg = toLocaleDigits(p.d) + ' ' + MONTHS[p.mo] + ' ' + toLocaleDigits(p.y);
  var hijri = hijriLabel(now);
  setDotted('date-label', hijri ? [greg, hijri] : [greg]);
}

// تُستدعى كل ثانية لكنها لا تلمس الصفحة إلا عند تغيّر النص (دقيقة جديدة، يوم جديد، ...)
function tick() {
  var now = nowMs();
  var p = cityParts(now);
  setText('clock-h', toLocaleDigits(p.h % 12 || 12));
  setText('clock-m', toLocaleDigits(pad2(p.mi)));
  setText('clock-period', p.h >= 12 ? 'م' : 'ص');

  var dn = dayNumber(now);
  if (dn !== lastDayNumber) {
    lastDayNumber = dn;
    refreshDate(now);
    renderMatch(); // "اليوم / بكرة" في المباراة تعتمد على اليوم
  }
  renderPrayer(now);
  renderRamadan(now);
}

// ═══════════ عدّاد الصلاة القادمة (محلي بالكامل) ═══════════
// Kotlin يدفع أوقات اليوم وبكرة كلحظات مطلقة (epoch ms) — والعدّ التنازلي يحسبه JavaScript هنا كل ثانية،
// فيستمر بالعمل حتى لو انقطع الإنترنت. بعد العشاء تصير "القادمة" هي فجر بكرة تلقائيًا.
var PRAYER_NAMES = { fajr: 'الفجر', dhuhr: 'الظهر', asr: 'العصر', maghrib: 'المغرب', isha: 'العشاء' };
var prayerEvents = null; // null = لم تصل بيانات بعد (جاري التحميل)

function updatePrayers(data) {
  var list = (data && data.events) || [];
  var clean = [];
  for (var i = 0; i < list.length; i++) {
    var e = list[i];
    if (e && PRAYER_NAMES[e.key] && typeof e.ts === 'number' && isFinite(e.ts)) {
      clean.push({ key: e.key, ts: e.ts });
    }
  }
  clean.sort(function (a, b) { return a.ts - b.ts; });
  prayerEvents = clean;
  renderPrayer(nowMs());
}

// "بعد ٥ ساعات و٥٠ دقيقة" — الدقائق تُقرَّب للأعلى (آخر دقيقة تظهر "بعد دقيقة" ثم تنتقل للصلاة التالية)
function untilText(ms) {
  var totalMin = Math.max(1, Math.ceil(ms / 60000));
  var h = Math.floor(totalMin / 60);
  var m = totalMin % 60;
  var parts = [];
  if (h > 0) parts.push(unitText(h, 'ساعة', 'ساعتين', 'ساعات', 'ساعة'));
  if (m > 0) parts.push(unitText(m, 'دقيقة', 'دقيقتين', 'دقائق', 'دقيقة'));
  return 'بعد ' + parts.join(' و');
}

function renderPrayer(now) {
  if (prayerEvents === null) {
    setText('prayer-name', '—');
    setText('prayer-time', '');
    setText('prayer-sub', 'جاري التحميل…');
    return;
  }
  var next = null;
  for (var i = 0; i < prayerEvents.length; i++) {
    if (prayerEvents[i].ts > now) { next = prayerEvents[i]; break; }
  }
  if (!next) {
    setText('prayer-name', '—');
    setText('prayer-time', '');
    setText('prayer-sub', 'أوقات الصلاة غير متاحة حاليًا');
    return;
  }
  var tomorrow = dayNumber(next.ts) - dayNumber(now) === 1;
  setText('prayer-name', PRAYER_NAMES[next.key]);
  setText('prayer-time', (tomorrow ? 'بكرة ' : '') + fmtTime12(next.ts));
  setText('prayer-sub', untilText(next.ts - now));
}

// ═══════════ عدّاد رمضان ═══════════
var ramadanTarget = null; // لحظة بداية رمضان (epoch ms)

function updateRamadan(data) {
  if (!data) return;
  if (typeof data.target === 'number' && data.target > 0) {
    ramadanTarget = data.target;
  } else if (typeof data.days === 'number') { // توافق مع نسخة Kotlin القديمة بدون target
    ramadanTarget = nowMs() + (((data.days * 24) + (data.hours || 0)) * 60 + (data.minutes || 0)) * 60000;
  }
  renderRamadan(nowMs());
}

function renderRamadan(now) {
  if (ramadanTarget === null) {
    setText('ramadan-value', '—');
    setText('ramadan-unit', '');
    setText('ramadan-sub', 'جاري التحميل…');
    return;
  }
  // عدد الأيام بفرق التقويم (مثل المرجع: ١١٣ يومًا)، ويتغيّر عند منتصف الليل
  var n = Math.max(0, dayNumber(ramadanTarget) - dayNumber(now));
  var value, unit = '';
  if (n === 0) value = 'اليوم';
  else if (n === 1) value = 'بكرة';
  else if (n === 2) value = 'يومان';
  else {
    value = toLocaleDigits(n);
    unit = n <= 10 ? 'أيام' : 'يومًا';
  }
  var t = cityParts(ramadanTarget);
  setText('ramadan-value', value);
  setText('ramadan-unit', unit);
  setText('ramadan-sub', 'يبدأ ' + WEEKDAYS[t.wd] + '، ' + toLocaleDigits(t.d) + ' ' + MONTHS[t.mo] + ' تقريبًا');
}

// ═══════════ الطقس ═══════════
function iconId(code) {
  var c = String(code || '');
  var night = c.charAt(2) === 'n';
  switch (c.slice(0, 2)) {
    case '01': return night ? 'i-clear-n' : 'i-clear-d';
    case '02': return night ? 'i-few-n' : 'i-few-d';
    case '03': case '04': return 'i-cloud';
    case '09': case '10': return 'i-rain';
    case '11': return 'i-thunder';
    case '13': return 'i-snow';
    case '50': return 'i-mist';
    default: return 'i-cloud';
  }
}

var lastForecastSig = '';

function updateWeather(data) {
  if (!data) return;
  $('weather-icon').setAttribute('href', '#' + iconId(data.icon));
  setText('weather-temp', toLocaleDigits(data.temp) + '°');
  setText('weather-desc', data.description || '');

  var daily = data.daily || [];
  var sig = JSON.stringify(daily);
  if (sig === lastForecastSig) return;
  lastForecastSig = sig;

  var html = '';
  for (var i = 0; i < daily.length && i < 5; i++) {
    var day = daily[i];
    html +=
      '<div class="fday">' +
        '<span>' + esc(i === 0 ? 'اليوم' : day.day) + '</span>' +
        '<svg class="wicon" viewBox="0 0 48 48" aria-hidden="true"><use href="#' + iconId(String(day.icon || '').slice(0, 2) + 'd') + '"></use></svg>' +
        '<span class="hi">' + toLocaleDigits(day.max) + '°</span>' +
        '<span class="lo">' + toLocaleDigits(day.min) + '°</span>' +
      '</div>';
  }
  $('weather-forecast').innerHTML = html;
}

// ═══════════ مباريات الفريق (تتبدل على الخمس كل 10 ثوانٍ) ═══════════
// أسماء الأندية بالعربية (الـ API يرسلها بالإنجليزية). الترتيب مهم: الأخص أولًا. ما لا يوجد هنا يُعرض بالإنجليزية.
var TEAM_NAMES = [
  ['chelsea', 'تشيلسي'], ['manchester united', 'مانشستر يونايتد'], ['manchester city', 'مانشستر سيتي'], ['tottenham', 'توتنهام'],
  ['arsenal', 'آرسنال'], ['liverpool', 'ليفربول'], ['newcastle', 'نيوكاسل'], ['aston villa', 'أستون فيلا'],
  ['brighton', 'برايتون'], ['west ham', 'وست هام'], ['crystal palace', 'كريستال بالاس'], ['brentford', 'برينتفورد'],
  ['fulham', 'فولهام'], ['everton', 'إيفرتون'], ['bournemouth', 'بورنموث'], ['wolverhampton', 'وولفرهامبتون'],
  ['wolves', 'وولفرهامبتون'], ['nottingham forest', 'نوتنغهام فورست'], ['leeds', 'ليدز يونايتد'],
  ['sunderland', 'سندرلاند'], ['burnley', 'بيرنلي'], ['leicester', 'ليستر سيتي'], ['southampton', 'ساوثهامبتون'],
  ['ipswich', 'إيبسويتش تاون'], ['luton', 'لوتون تاون'], ['sheffield united', 'شيفيلد يونايتد'],
  ['norwich', 'نوريتش سيتي'], ['middlesbrough', 'ميدلزبره'], ['west bromwich', 'وست بروميتش'],
  ['coventry', 'كوفنتري سيتي'], ['watford', 'واتفورد'], ['blackburn', 'بلاكبيرن'], ['stoke', 'ستوك سيتي'],
  ['real madrid', 'ريال مدريد'], ['barcelona', 'برشلونة'], ['atletico', 'أتلتيكو مدريد'], ['sevilla', 'إشبيلية'],
  ['valencia', 'فالنسيا'], ['villarreal', 'فياريال'], ['real sociedad', 'ريال سوسيداد'], ['athletic club', 'أتلتيك بيلباو'],
  ['real betis', 'ريال بيتيس'], ['girona', 'جيرونا'],
  ['bayern', 'بايرن ميونخ'], ['dortmund', 'بوروسيا دورتموند'], ['monchengladbach', 'بوروسيا مونشنغلادباخ'], ['leverkusen', 'باير ليفركوزن'],
  ['rb leipzig', 'لايبزيغ'], ['eintracht', 'آينتراخت فرانكفورت'], ['stuttgart', 'شتوتغارت'],
  ['paris saint germain', 'باريس سان جيرمان'], ['marseille', 'مارسيليا'], ['monaco', 'موناكو'],
  ['lille', 'ليل'], ['lyon', 'ليون'],
  ['juventus', 'يوفنتوس'], ['internazionale', 'إنتر ميلان'], ['inter milan', 'إنتر ميلان'], ['ac milan', 'ميلان'],
  ['napoli', 'نابولي'], ['roma', 'روما'], ['lazio', 'لاتسيو'], ['atalanta', 'أتالانتا'], ['fiorentina', 'فيورنتينا'],
  ['benfica', 'بنفيكا'], ['porto', 'بورتو'], ['sporting', 'سبورتينغ لشبونة'], ['ajax', 'أياكس'], ['psv', 'آيندهوفن'],
  ['feyenoord', 'فينورد'], ['celtic', 'سلتيك'], ['rangers', 'رينجرز'], ['galatasaray', 'غلطة سراي'],
  ['fenerbahce', 'فنربخشة'], ['besiktas', 'بشكتاش'], ['club brugge', 'كلوب بروج'], ['shakhtar', 'شاختار دونيتسك'],
  ['salzburg', 'ريد بول سالزبورغ'], ['young boys', 'يانغ بويز'], ['olympiacos', 'أولمبياكوس'],
  ['panathinaikos', 'باناثينايكوس'], ['slavia', 'سلافيا براغ'], ['sparta', 'سبارتا براغ'],
  ['copenhagen', 'كوبنهاغن'], ['qarabag', 'قاراباغ'], ['bodo', 'بودو غليمت'], ['pafos', 'بافوس'],
  ['paphos', 'بافوس'], ['kairat', 'كايرات'], ['union saint gilloise', 'يونيون سان جيلواز']
];

var COMPETITION_NAMES = [
  ['premier league', 'الدوري الإنجليزي'], ['champions league', 'دوري أبطال أوروبا'],
  ['europa league', 'الدوري الأوروبي'], ['conference league', 'دوري المؤتمر'], ['fa cup', 'كأس الاتحاد'],
  ['efl cup', 'كأس الرابطة'], ['league cup', 'كأس الرابطة'], ['carabao', 'كأس الرابطة'],
  ['community shield', 'الدرع الخيرية'], ['club world cup', 'كأس العالم للأندية'],
  ['super cup', 'السوبر الأوروبي'], ['friendl', 'مباراة ودية']
];

function normName(s) {
  s = String(s || '').toLowerCase();
  try { s = s.normalize('NFD').replace(/[̀-ͯ]/g, ''); } catch (e) { /* متصفح قديم بلا normalize */ }
  return s.replace(/[^a-z0-9 ]+/g, ' ').replace(/\s+/g, ' ').replace(/^ | $/g, '');
}

function lookupName(table, name) {
  var n = normName(name);
  for (var i = 0; i < table.length; i++) {
    if (n.indexOf(table[i][0]) !== -1) return table[i][1];
  }
  return null;
}

function arTeam(name) {
  var ar = lookupName(TEAM_NAMES, name);
  if (ar) return ar;
  return String(name || '').replace(/\b(FC|AFC|CF|SC)\b\.?/gi, '').replace(/\s+/g, ' ').replace(/^ | $/g, '');
}

function arCompetition(name) {
  return lookupName(COMPETITION_NAMES, name) || String(name || '');
}

var matchList = [];
var matchIdx = 0;
var matchSig = '';
var matchGen = 0; // يُلغي أي تبديل معلّق إذا وصلت قائمة جديدة في منتصف التلاشي

function matchWhen(m) {
  if (m.ts) {
    var diff = dayNumber(m.ts) - dayNumber(nowMs());
    var day;
    if (diff === 0) day = 'اليوم';
    else if (diff === 1) day = 'بكرة';
    else {
      var p = cityParts(m.ts);
      day = WEEKDAYS[p.wd] + ' ' + toLocaleDigits(p.d) + ' ' + MONTHS[p.mo];
    }
    return day + ' ' + fmtTime12(m.ts);
  }
  return ((m.date || '') + ' ' + toLocaleDigits(m.time || '')).replace(/^ | $/g, '');
}

function renderDots() {
  var el = $('match-dots');
  var html = '';
  if (matchList.length > 1) {
    for (var i = 0; i < matchList.length; i++) html += '<i' + (i === matchIdx ? ' class="on"' : '') + '></i>';
  }
  el.innerHTML = html;
}

// يصغّر خط العنوان تدريجيًا (من 38 إلى 22 كحد أدنى) حتى يتسع في عرض العمود — بدل قصّه بنقاط
function fitTitle(el) {
  var size = 38;
  while (el.scrollWidth > el.clientWidth + 1 && size > 22) {
    size -= 2;
    el.style.fontSize = 'calc(var(--px) * ' + size + ')';
  }
}

function renderMatch() {
  var titleEl = $('match-title');
  var m = matchList[matchIdx];
  if (!m) {
    titleEl.className = 'value';
    titleEl.textContent = '—';
    setDotted('match-sub', ['لا توجد مباريات قادمة متاحة حاليًا']);
    return;
  }
  var opp = arTeam(m.opponent);
  var first = m.isHome ? TEAM_NAME : opp;
  var second = m.isHome ? opp : TEAM_NAME;
  titleEl.className = 'value';
  titleEl.style.fontSize = '';
  titleEl.textContent = '';
  titleEl.appendChild(document.createTextNode(first));
  var vs = document.createElement('span');
  vs.className = 'vs';
  vs.textContent = '×';
  titleEl.appendChild(vs);
  titleEl.appendChild(document.createTextNode(second));
  fitTitle(titleEl);

  var parts = [esc(matchWhen(m))];
  var comp = arCompetition(m.competition);
  if (comp) parts.push(esc(comp));
  parts.push(m.isHome ? 'أرضي' : 'خارجي');
  setDotted('match-sub', parts);
}

function updateMatches(matches) {
  var list = matches || [];
  var sig = JSON.stringify(list);
  if (sig === matchSig) return; // لا تغيير: لا نقطع دوران العرض الحالي
  matchSig = sig;
  matchList = list;
  matchIdx = 0;
  matchGen++;
  renderDots();
  renderMatch();
}

function rotateMatch() {
  if (matchList.length < 2) return;
  var swap = $('match-swap');
  var gen = matchGen;
  swap.classList.add('out');
  setTimeout(function () {
    if (gen === matchGen) {
      matchIdx = (matchIdx + 1) % matchList.length;
      renderDots();
      renderMatch();
    }
    swap.classList.remove('out');
  }, MATCH_FADE_MS);
}

// ═══════════ خلفية الصور المتبدلة ═══════════
// Kotlin يقرأ أسماء الملفات من assets/backgrounds ويدفعها هنا مرة واحدة. بدون صور تبقى الخلفية navy.
var bgList = [];
var bgSig = '';
var bgIdx = -1;
var bgFront = -1; // رقم الطبقة الظاهرة حاليًا (0 أو 1)، -1 = لا شيء بعد
var bgTimer = null;
var bgLayers = [$('bg-a'), $('bg-b')];

function bgUrl(name) {
  if (/^(data:|blob:|https?:|file:)/.test(name)) return name; // للمعاينة فقط
  return 'backgrounds/' + encodeURIComponent(name);
}

function shuffle(arr) {
  for (var i = arr.length - 1; i > 0; i--) {
    var j = Math.floor(Math.random() * (i + 1));
    var t = arr[i]; arr[i] = arr[j]; arr[j] = t;
  }
  return arr;
}

function setBackgrounds(names) {
  var list = [];
  for (var i = 0; i < (names || []).length; i++) {
    if (typeof names[i] === 'string' && names[i]) list.push(names[i]);
  }
  var sig = list.join('|');
  if (sig === bgSig) return;
  bgSig = sig;

  if (bgTimer) { clearInterval(bgTimer); bgTimer = null; }
  bgList = shuffle(list); // ترتيب عشوائي في كل تشغيل
  bgIdx = -1;

  if (!bgList.length) {
    bgFront = -1;
    $('stage').className = 'stage';
    for (var k = 0; k < 2; k++) { bgLayers[k].className = 'bg-layer'; bgLayers[k].style.backgroundImage = 'none'; }
    return;
  }
  $('stage').className = 'stage has-photo';
  showNextBg();
  if (bgList.length > 1) bgTimer = setInterval(showNextBg, BG_INTERVAL_MS);
}

function showNextBg() {
  if (!bgList.length) return;
  bgIdx = (bgIdx + 1) % bgList.length;
  var name = bgList[bgIdx];
  var url = bgUrl(name);
  var applied = false;
  var img = new Image();

  function apply() {
    if (applied) return;
    applied = true;
    var back = bgFront === 0 ? 1 : 0;
    var layer = bgLayers[back];
    var old = bgFront >= 0 ? bgLayers[bgFront] : null;
    layer.style.backgroundImage = 'url("' + url + '")';
    layer.style.zIndex = 2;      // الجديدة فوق القديمة، فتتلاشى فوقها بدون وميض
    layer.className = 'bg-layer on';
    bgFront = back;
    if (old) {
      old.style.zIndex = 1;
      setTimeout(function () {
        if (bgLayers[bgFront] !== old) { // نحرر ذاكرة الصورة القديمة بعد اكتمال التلاشي
          old.className = 'bg-layer';
          old.style.backgroundImage = 'none';
        }
      }, BG_FADE_MS + 300);
    }
  }

  img.onload = function () {
    // decode() يفك ضغط الصورة قبل التلاشي حتى لا يتقطع الانتقال (إن كانت متاحة)
    if (img.decode) { img.decode().then(apply, apply); } else { apply(); }
  };
  img.onerror = function () {
    // ملف تالف: نحذفه من القائمة ونجرّب التالي
    var at = bgList.indexOf(name);
    if (at !== -1) bgList.splice(at, 1);
    if (bgIdx >= bgList.length) bgIdx = bgList.length - 1;
    if (!bgList.length && bgTimer) { clearInterval(bgTimer); bgTimer = null; }
    else if (bgList.length) showNextBg();
  };
  img.src = url;
}

// ═══════════ إعدادات المدينة والفريق (من ApiConfig.kt) ═══════════
// يدفعها Kotlin مرة واحدة عند تحميل الصفحة: { city, team, tz, offsetMs }.
// تغيّر نصوص «طقس ...» و«مباراة ... القادمة»، وتضبط المنطقة الزمنية للساعة والتواريخ والأوقات.
function setConfig(cfg) {
  if (!cfg) return;
  if (typeof cfg.city === 'string' && cfg.city) CITY_NAME = cfg.city;
  if (typeof cfg.team === 'string' && cfg.team) TEAM_NAME = cfg.team;
  if (typeof cfg.tz === 'string' && cfg.tz !== TZ_NAME) { TZ_NAME = cfg.tz; tzFmt = null; }
  if (typeof cfg.offsetMs === 'number' && isFinite(cfg.offsetMs)) TZ_FALLBACK_MS = cfg.offsetMs;

  // textContent وليس innerHTML: الأسماء نصوص عادية ولا تُفسَّر كـ HTML أبدًا
  $('weather-label').textContent = 'طقس ' + CITY_NAME;
  $('weather-box').setAttribute('aria-label', 'طقس ' + CITY_NAME);
  $('match-label').textContent = 'مباراة ' + TEAM_NAME + ' القادمة';
  $('match-box').setAttribute('aria-label', 'مباريات ' + TEAM_NAME + ' القادمة');

  // نعيد رسم كل ما يعتمد على التوقيت أو اسم الفريق (التاريخ، الساعة، الصلاة، رمضان، عنوان المباراة)
  lastDayNumber = null;
  tick();
}

// ═══════════ التشغيل ═══════════
// الدوال التالية يستدعيها كود Kotlin (ChelseaDreamService) عبر evaluateJavascript:
//   setConfig({...}) · setBackgrounds([...أسماء]) · updatePrayers({events}) · updateRamadan({target}) · updateWeather({...}) · updateMatches([...])
tick();
setInterval(tick, 1000);
setInterval(rotateMatch, MATCH_ROTATE_MS);
// بعد تحميل الخط نعيد قياس عنوان المباراة (القياس قبل وصول الخط يكون بخط بديل أضيق/أعرض)
if (document.fonts && document.fonts.ready) document.fonts.ready.then(renderMatch);
