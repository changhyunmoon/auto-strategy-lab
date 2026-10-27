/* 변수 탐색: 변수 하나의 설명 · 가격과 함께 · 분포 · N봉 뒤 수익률 · 지속성 · 시간대 · 관련 변수 */
$(function () {
  var $page = $('#featPage');
  if (!$page.length) return;

  var datasetId = $page.data('id');
  var params = new URLSearchParams(location.search);
  var col = String($page.data('selected'));
  var horizon = Number(params.get('h')) || 6;
  var meta = {};
  FEATURE_COLUMNS.forEach(function (c) { meta[c.name] = c; });

  // URL 에 종목 · 간격이 있으면 그것으로 시작한다
  if (params.get('asset')) $('input[name="fAsset"][value="' + params.get('asset') + '"]').prop('checked', true);
  if (params.get('interval')) $('input[name="fInterval"][value="' + params.get('interval') + '"]').prop('checked', true);

  function assetId() { return $('input[name="fAsset"]:checked').val(); }
  function assetCode() { return $('input[name="fAsset"]:checked').data('code'); }
  function interval() { return $('input[name="fInterval"]:checked').val(); }
  function intervalMinutes() { return parseInt(interval(), 10); }

  var UP = '#e03e3e', DOWN = '#2383e2', MUTED = '#a5a29c';
  var charts = {};
  function chart(id) {
    if (!charts[id]) charts[id] = echarts.init(document.getElementById(id), 'clean');
    return charts[id];
  }
  function loading(id) { chart(id).showLoading('default', { text: '', color: '#2383e2', maskColor: 'rgba(255,255,255,.6)' }); }

  /* ---------- 숫자 · 값 표시 ---------- */

  function fmt(v) {
    if (v == null || isNaN(v)) return '–';
    var a = Math.abs(v);
    if (a >= 1000) return App.formatNumber(Math.round(v * 100) / 100);
    if (a === 0) return '0';
    if (a < 0.001) return Number(v).toExponential(2);
    return String(Number(Number(v).toPrecision(4)));
  }
  function pct(v, d) { return v == null ? '–' : (v > 0 ? '+' : '') + v.toFixed(d == null ? 3 : d) + '%'; }

  // 값 종류가 적은 변수는 숫자 대신 뜻으로 보여준다
  var DIR = { '-1': '−1 (하락 쪽)', '1': '+1 (상승 쪽)' };
  var VALUE_LABELS = {
    day_of_week: { 0: '월', 1: '화', 2: '수', 3: '목', 4: '금', 5: '토', 6: '일' },
    is_weekend: { 0: '평일', 1: '주말' },
    ema_alignment: { '-1': '역배열', 0: '섞임', 1: '정배열' },
    ema_cross_dir: DIR,
    macd_cross_dir: DIR
  };
  function valueLabel(v) {
    var m = VALUE_LABELS[col];
    return m && m[String(v)] != null ? m[String(v)] : fmt(v);
  }
  function barsText(n) {
    var min = n * intervalMinutes();
    return n + '봉 (' + (min >= 60 && min % 60 === 0 ? (min / 60) + '시간' : min + '분') + ')';
  }

  /* ---------- 왼쪽 목록 · 설명 ---------- */

  function renderNav() {
    $('.feat-nav-item').removeClass('active').filter('[data-col="' + col + '"]').addClass('active');
  }

  function renderDoc() {
    var c = meta[col] || { name: col, featureName: '', group: '', param: '' };
    var doc = FEATURE_DOCS[col];
    $('#fName').text(col);
    var $meta = $('#fMeta').empty();
    $meta.append($('<span class="feat-title"></span>').text(c.featureName));
    if (c.group && c.group !== '-' && c.group !== '?') {
      $meta.append($('<span class="tag"></span>').text(c.group + '. ' + (GROUP_LABELS[c.group] || '')));
    } else if (c.group === '-') {
      $meta.append($('<span class="tag"></span>').text('봉 값'));
    }
    if (c.param) $meta.append($('<span class="muted small"></span>').text('설정 ' + c.param));

    var $doc = $('#fDoc').empty();
    if (!doc) {
      $doc.append($('<div class="muted"></div>').text('이 변수에 대한 설명이 아직 없습니다.'));
      return;
    }
    $doc.append($('<p class="feat-summary"></p>').text(doc.summary));
    var $dl = $('<dl class="feat-dl"></dl>');
    [['정의', doc.definition], ['계산', doc.formula], ['값 읽기', doc.reading], ['전략 활용', doc.usage], ['주의', doc.caution]]
      .forEach(function (r) {
        if (!r[1]) return;
        $dl.append($('<dt></dt>').text(r[0]));
        $dl.append($('<dd></dd>').text(r[1]).toggleClass('mono-inline', r[0] === '계산'));
      });
    $doc.append($dl);
  }

  function refLines() { return (FEATURE_DOCS[col] && FEATURE_DOCS[col].refLines) || []; }

  /* ---------- 가격과 함께 보기 ---------- */

  function loadSeries() {
    loading('tsChart');
    App.api('GET', '/api/viz/' + datasetId + '/series', { assetId: assetId(), interval: interval(), columns: col })
      .done(function (r) {
        var c = chart('tsChart');
        c.hideLoading();
        var values = r.series[col] || [];
        var discrete = lastProfile && lastProfile.stats && lastProfile.stats.discrete;
        c.setOption({
          animation: false,
          legend: { top: 0, left: 70, data: [assetCode(), col] },
          tooltip: { trigger: 'axis', axisPointer: { type: 'cross' } },
          axisPointer: { link: [{ xAxisIndex: 'all' }] },
          grid: [{ left: 70, right: 20, top: 30, height: 220 }, { left: 70, right: 20, top: 290, height: 110 }],
          xAxis: [0, 1].map(function (i) {
            return { type: 'category', data: r.times, gridIndex: i, axisLabel: { show: i === 1 }, axisPointer: { label: { show: i === 1 } } };
          }),
          yAxis: [{ scale: true, gridIndex: 0, splitNumber: 4 }, { scale: true, gridIndex: 1, splitNumber: 3 }],
          dataZoom: [{ type: 'inside', xAxisIndex: [0, 1] }, { type: 'slider', xAxisIndex: [0, 1], bottom: 8, height: 20 }],
          series: [
            { name: assetCode(), type: 'candlestick', data: r.candles, xAxisIndex: 0, yAxisIndex: 0 },
            { name: col, type: 'line', data: values, xAxisIndex: 1, yAxisIndex: 1, showSymbol: false,
              step: discrete ? 'end' : false, lineStyle: { width: 1.2 }, connectNulls: false,
              markLine: refLines().length ? { silent: true, symbol: 'none', label: { show: true, position: 'insideEndTop', color: MUTED, fontSize: 10 },
                lineStyle: { color: '#c9c9c5', type: 'dashed' }, data: refLines().map(function (y) { return { yAxis: y }; }) } : undefined }
          ]
        }, true);
        $('#tsInfo').text(App.formatNumber(r.totalBars) + '봉 · ' + r.bucketText + (r.bucketBars > 1 ? ' · 점이 많아 평균으로 묶음' : ''));
      }).fail(function () { chart('tsChart').hideLoading(); });
  }

  /* ---------- 분석 (분포 · 수익률 · 지속성 · 시간대 · 관련) ---------- */

  var lastProfile = null;

  function loadProfile() {
    ['histChart', 'fwdChart', 'acfChart', 'hourChart'].forEach(loading);
    $('#fInsights').html('<li class="muted">계산 중…</li>');
    return App.api('GET', '/api/viz/' + datasetId + '/profile',
      { assetId: assetId(), interval: interval(), column: col, horizon: horizon })
      .done(function (p) {
        lastProfile = p;
        Object.keys(charts).forEach(function (k) { if (k !== 'tsChart') charts[k].hideLoading(); });
        renderHorizonChips(p.horizons);
        renderHist(p.stats);
        renderStats(p.stats);
        renderForward(p);
        renderAcf(p.acf);
        renderHourly(p.hourly);
        renderRelated(p.related);
        renderInsights(p);
      }).fail(function () { Object.keys(charts).forEach(function (k) { charts[k].hideLoading(); }); });
  }

  function renderHorizonChips(list) {
    var $g = $('#horizonChips').empty();
    list.forEach(function (h) {
      var $l = $('<label class="chip"></label>').attr('title', barsText(h));
      $l.append($('<input type="radio" name="fHorizon">').val(h).prop('checked', h === horizon)).append(document.createTextNode(h + '봉'));
      $g.append($l);
    });
  }

  function renderHist(s) {
    var h = s.hist || { centers: [], counts: [] };
    var labels = h.centers.map(function (v) { return s.discrete ? valueLabel(v) : fmt(v); });
    // 기준선은 가장 가까운 막대 위치에 긋는다
    var marks = s.discrete ? [] : refLines().filter(function (y) {
      return h.centers.length && y >= h.centers[0] && y <= h.centers[h.centers.length - 1];
    }).map(function (y) {
      var best = 0;
      h.centers.forEach(function (c, i) { if (Math.abs(c - y) < Math.abs(h.centers[best] - y)) best = i; });
      return { xAxis: labels[best], label: { formatter: fmt(y) } };
    });
    chart('histChart').setOption({
      grid: { left: 54, right: 16, top: 16, bottom: 40 },
      tooltip: { trigger: 'axis', formatter: function (ps) { var p = ps[0]; return (s.discrete ? '' : '≈ ') + p.name + '<br><b>' + App.formatNumber(p.value) + '</b>봉'; } },
      xAxis: { type: 'category', data: labels, axisLabel: { hideOverlap: true } },
      yAxis: { type: 'value' },
      series: [{ type: 'bar', data: h.counts, barCategoryGap: s.discrete ? '40%' : '6%', itemStyle: { color: '#2383e2', opacity: .75 },
        markLine: marks.length ? { silent: true, symbol: 'none', lineStyle: { color: MUTED, type: 'dashed' }, label: { color: MUTED, fontSize: 10 }, data: marks } : undefined }]
    }, true);
  }

  function renderStats(s) {
    var rows = [
      ['개수', App.formatNumber(s.count) + '봉'],
      [term('missing', '결측 비율'), App.pct(s.missingRate, 2)],
      ['평균', fmt(s.mean)], [term('stdev', '표준편차'), fmt(s.std)],
      ['최솟값', fmt(s.min)], [term('quantile', '5% · 95%'), fmt(s.q05) + ' · ' + fmt(s.q95)],
      [term('quantile', '25% · 중앙값 · 75%'), fmt(s.q25) + ' · ' + fmt(s.median) + ' · ' + fmt(s.q75)],
      ['최댓값', fmt(s.max)]
    ];
    if (s.hist && s.hist.clipped) rows.push(['양 끝 합친 값', App.formatNumber(s.hist.clipped) + '봉 (상하위 0.5%)']);
    $('#statsBody').html(rows.map(function (r) { return '<tr><td class="muted">' + r[0] + '</td><td class="num">' + r[1] + '</td></tr>'; }).join(''));
  }
  function term(key, text) { return '<span data-term="' + key + '">' + text + '</span>'; }

  function renderForward(p) {
    var f = p.forward, bs = f.buckets || [];
    var disc = p.stats.discrete;
    var labels = bs.map(function (b, i) {
      return disc ? valueLabel(b.lo) : (i + 1) + '구간';
    });
    chart('fwdChart').setOption({
      grid: { left: 60, right: 50, top: 30, bottom: 40 },
      legend: { top: 0, right: 0, data: ['평균 수익률', '오른 비율'] },
      tooltip: { trigger: 'axis', formatter: function (ps) {
        var b = bs[ps[0].dataIndex];
        var range = disc ? valueLabel(b.lo) : fmt(b.lo) + ' ~ ' + fmt(b.hi);
        return '<b>' + range + '</b><br>' + App.formatNumber(b.count) + '봉<br>' + barsText(p.horizon) + ' 뒤 평균 ' + pct(b.meanPct)
          + '<br>오른 비율 ' + App.pct(b.upRate, 1);
      } },
      xAxis: { type: 'category', data: labels, name: disc ? '' : '낮음 → 높음', nameLocation: 'middle', nameGap: 26, nameTextStyle: { color: MUTED, fontSize: 11 } },
      yAxis: [
        { type: 'value', axisLabel: { formatter: function (v) { return v.toFixed(2) + '%'; } } },
        { type: 'value', min: function (v) { return Math.max(0, Math.floor(v.min * 20) / 20); }, max: function (v) { return Math.min(1, Math.ceil(v.max * 20) / 20); },
          splitLine: { show: false }, axisLabel: { formatter: function (v) { return Math.round(v * 100) + '%'; } } }
      ],
      series: [
        { name: '평균 수익률', type: 'bar', barCategoryGap: '25%',
          data: bs.map(function (b) { return { value: b.meanPct, itemStyle: { color: b.meanPct >= 0 ? UP : DOWN, opacity: .75 } }; }),
          markLine: f.meanPct == null ? undefined : { silent: true, symbol: 'none', lineStyle: { color: MUTED, type: 'dashed' },
            label: { formatter: '전체 평균', color: MUTED, fontSize: 10 }, data: [{ yAxis: f.meanPct }] } },
        { name: '오른 비율', type: 'line', yAxisIndex: 1, data: bs.map(function (b) { return b.upRate; }), symbolSize: 5,
          lineStyle: { width: 1.5, color: '#9065b0' }, itemStyle: { color: '#9065b0' } }
      ]
    }, true);

    var ic = icAt(p, p.horizon);
    $('#icText').html(term('ic', 'IC') + ' <b>' + (ic == null ? '–' : ic.toFixed(3)) + '</b> <span class="faint">' + icWord(ic) + '</span>');
    $('#icByHorizon').text('기간별 IC — ' + p.icByHorizon.map(function (x) {
      return x.horizon + '봉 ' + (x.ic == null ? '–' : x.ic.toFixed(3));
    }).join(' · ') + '  (' + App.formatNumber(f.count) + '봉 기준, 사이에 빈 구간이 있는 봉 제외)');
  }

  function icAt(p, h) {
    var x = p.icByHorizon.filter(function (r) { return r.horizon === h; })[0];
    return x ? x.ic : null;
  }
  function icWord(ic) {
    if (ic == null) return '';
    var a = Math.abs(ic);
    return a < 0.02 ? '거의 없음' : a < 0.05 ? '약함' : a < 0.1 ? '뚜렷한 편' : '강함 (과적합 · 데이터 누출도 의심)';
  }

  function renderAcf(acf) {
    chart('acfChart').setOption({
      grid: { left: 50, right: 16, top: 16, bottom: 40 },
      tooltip: { trigger: 'axis', formatter: function (ps) { return ps[0].name + '봉 뒤와의 상관 <b>' + fmt(ps[0].value) + '</b>'; } },
      xAxis: { type: 'category', data: acf.map(function (_, i) { return i + 1; }), name: '몇 봉 뒤', nameLocation: 'middle', nameGap: 26, nameTextStyle: { color: MUTED, fontSize: 11 } },
      yAxis: { type: 'value', min: function (v) { return Math.min(0, Math.floor(v.min * 10) / 10); }, max: 1 },
      series: [{ type: 'bar', data: acf, itemStyle: { color: '#0f7b6c', opacity: .7 },
        markLine: { silent: true, symbol: 'none', lineStyle: { color: MUTED, type: 'dashed' }, label: { color: MUTED, fontSize: 10 }, data: [{ yAxis: 0.5 }] } }]
    }, true);
  }

  function renderHourly(h) {
    chart('hourChart').setOption({
      grid: { left: 60, right: 16, top: 16, bottom: 40 },
      tooltip: { trigger: 'axis', formatter: function (ps) {
        var i = ps[0].dataIndex;
        return 'KST ' + i + '시<br>평균 <b>' + fmt(ps[0].value) + '</b><br>' + App.formatNumber(h.count[i]) + '봉';
      } },
      xAxis: { type: 'category', data: h.mean.map(function (_, i) { return i + '시'; }), axisLabel: { interval: 2 } },
      yAxis: { type: 'value', scale: true, axisLabel: { formatter: fmt } },
      series: [{ type: 'bar', data: h.mean, itemStyle: { color: '#d9730d', opacity: .7 } }]
    }, true);
  }

  function renderRelated(list) {
    var $b = $('#relatedBody').empty();
    if (!list.length) { $b.append('<tr><td class="muted">비교할 다른 변수가 없습니다.</td></tr>'); return; }
    list.forEach(function (r) {
      var m = meta[r.column] || {};
      var a = Math.abs(r.r);
      var $bar = $('<span class="corr-bar"></span>').append($('<span></span>').css({ width: (a * 100) + '%', background: r.r >= 0 ? UP : DOWN }));
      $b.append($('<tr class="clickable"></tr>').attr('data-col', r.column)
        .append($('<td></td>').append($('<span class="mono row-title"></span>').text(r.column)))
        .append($('<td class="muted"></td>').text(m.featureName || ''))
        .append($('<td style="width:40%"></td>').append($bar))
        .append($('<td class="num"></td>').text(r.r.toFixed(2)))
        .append($('<td></td>').append(a >= 0.8 ? $('<span class="tag tag-yellow"></span>').text('거의 같은 정보') : '')));
    });
  }

  /* ---------- 데이터로 본 특징 (자동 문장) ---------- */

  function renderInsights(p) {
    var s = p.stats, out = [];
    $('#fInsightScope').text('· ' + assetCode() + ' · ' + interval().replace('m', '분') + '봉');
    if (!s.count) { $('#fInsights').html('<li>값이 있는 봉이 없습니다.</li>'); return; }

    if (s.missingRate > 0.01) out.push('값이 비어 있는 봉이 <b>' + App.pct(s.missingRate, 1) + '</b> 입니다 (빈 구간 · 거래 없음 · 필요한 데이터 없음). 학습 때 평균으로 채워집니다.');
    if (s.discrete) {
      var h = s.hist, top = 0;
      h.counts.forEach(function (c, i) { if (c > h.counts[top]) top = i; });
      out.push('값이 <b>' + h.centers.length + '가지</b>뿐인 변수입니다. 가장 흔한 값은 <b>' + valueLabel(h.centers[top]) + '</b> (' + App.pct(h.counts[top] / s.count, 1) + ').');
    } else {
      out.push('봉의 90% 가 <b>' + fmt(s.q05) + ' ~ ' + fmt(s.q95) + '</b> 사이에 있습니다 (중앙값 ' + fmt(s.median) + ').');
      if (s.zeroRate > 0.2) out.push('값이 정확히 0 인 봉이 ' + App.pct(s.zeroRate, 1) + ' 입니다. 거래가 없는 봉이 많을 수 있습니다.');
      var tail = s.std > 0 ? Math.max(s.max - s.q95, s.q05 - s.min) / s.std : 0;
      if (tail > 6) out.push('평소 범위를 크게 벗어나는 튀는 값이 있습니다 (표준편차의 ' + tail.toFixed(0) + '배). 급등락 구간일 수 있습니다.');
    }

    var a1 = p.acf[0];
    if (a1 != null) {
      var half = p.acf.findIndex(function (v) { return v == null || v < 0.5; });
      if (a1 >= 0.9) out.push('천천히 변하는 값입니다 (1봉 뒤와의 상관 ' + a1.toFixed(2) + (half > 0 ? ', ' + barsText(half + 1) + ' 뒤에 0.5 아래로' : ', 30봉 뒤에도 0.5 이상') + '). 「지금 어떤 국면인지」를 알려 주는 성격입니다.');
      else if (a1 < 0.3) out.push('봉마다 새로 바뀌는 값입니다 (1봉 뒤와의 상관 ' + a1.toFixed(2) + '). 잡음이 많아 단독 신호로는 흔들리기 쉽습니다.');
    }

    var ic = icAt(p, p.horizon), bs = p.forward.buckets || [];
    if (ic != null) {
      var dir = ic > 0 ? '값이 <b>클수록</b> 이후 수익률이 높았습니다 (추세 · 모멘텀 쪽)' : '값이 <b>클수록</b> 이후 수익률이 낮았습니다 (되돌림 쪽)';
      out.push(barsText(p.horizon) + ' 뒤 수익률과의 순위 상관(IC)은 <b>' + ic.toFixed(3) + '</b> — ' + icWord(ic) + '. '
        + (Math.abs(ic) >= 0.02 ? dir + '.' : '단독으로는 방향을 거의 구분하지 못합니다. 다른 변수와 함께 쓸 때 쓸모가 있을 수 있습니다.'));
    }
    if (bs.length >= 2) {
      var best = bs.reduce(function (m, b) { return b.meanPct > m.meanPct ? b : m; });
      var worst = bs.reduce(function (m, b) { return b.meanPct < m.meanPct ? b : m; });
      var range = function (b) { return s.discrete ? valueLabel(b.lo) : fmt(b.lo) + ' ~ ' + fmt(b.hi); };
      var spread = best.meanPct - worst.meanPct;
      out.push('구간별 평균 수익률은 <b>' + range(best) + '</b> 에서 가장 높고 (' + pct(best.meanPct) + '), <b>' + range(worst) + '</b> 에서 가장 낮습니다 (' + pct(worst.meanPct) + ').'
        + (spread < 0.1 ? ' 차이 ' + spread.toFixed(3) + '%p 는 왕복 수수료(약 0.1%)보다 작아서 이것만으로 수익을 내기는 어렵습니다.' : ''));
    }
    var bestH = p.icByHorizon.filter(function (x) { return x.ic != null; })
      .reduce(function (m, x) { return !m || Math.abs(x.ic) > Math.abs(m.ic) ? x : m; }, null);
    if (bestH && bestH.horizon !== p.horizon && Math.abs(bestH.ic) >= 0.02) {
      out.push('관계가 가장 뚜렷한 기간은 <b>' + barsText(bestH.horizon) + '</b> 입니다 (IC ' + bestH.ic.toFixed(3) + ').');
    }

    var hm = p.hourly.mean.filter(function (v) { return v != null; });
    if (!s.discrete && hm.length === 24 && s.std > 0 && col.indexOf('tod_') !== 0) {
      var maxH = p.hourly.mean.indexOf(Math.max.apply(null, hm)), minH = p.hourly.mean.indexOf(Math.min.apply(null, hm));
      if ((Math.max.apply(null, hm) - Math.min.apply(null, hm)) / s.std > 0.3) {
        out.push('시간대 차이가 있습니다: KST <b>' + maxH + '시</b>에 가장 크고 <b>' + minH + '시</b>에 가장 작습니다.');
      }
    }
    var twin = (p.related || []).filter(function (r) { return Math.abs(r.r) >= 0.8; });
    if (twin.length) {
      out.push('<span class="mono">' + twin.map(function (r) { return $('<i>').text(r.column).html(); }).join(', ') + '</span> 와(과) 상관이 ±0.8 이상입니다. 거의 같은 정보일 수 있습니다.');
    }
    $('#fInsights').html(out.map(function (t) { return '<li>' + t + '</li>'; }).join(''));
  }

  /* ---------- 상태 바꾸기 ---------- */

  function syncUrl() {
    var q = new URLSearchParams({ col: col, asset: assetId(), interval: interval(), h: horizon });
    history.replaceState(null, '', location.pathname + '?' + q.toString());
  }

  function loadAll() {
    syncUrl();
    renderNav();
    renderDoc();
    // 계단선 여부(값 종류가 적은지)를 알기 위해 분석을 먼저 받고 차트를 그린다
    lastProfile = null;
    loadProfile().always(loadSeries);
  }

  $('.feat-nav').on('click', '.feat-nav-item', function (e) {
    e.preventDefault();
    col = $(this).data('col');
    loadAll();
    window.scrollTo({ top: 0, behavior: 'smooth' });
  });
  $('#relatedBody').on('click', 'tr[data-col]', function () {
    col = $(this).data('col');
    loadAll();
    var $item = $('.feat-nav-item[data-col="' + col + '"]');
    if ($item.length) $item[0].scrollIntoView({ block: 'nearest' });
    window.scrollTo({ top: 0, behavior: 'smooth' });
  });
  $('#horizonChips').on('change', 'input', function () { horizon = Number(this.value); syncUrl(); loadProfile(); });
  $('input[name="fAsset"], input[name="fInterval"]').on('change', loadAll);
  $('#datasetSel').on('change', function () { location.href = '/datasets/' + this.value + '/features?col=' + encodeURIComponent(col); });
  $('#featSearch').on('input', function () {
    var q = this.value.trim().toLowerCase();
    $('.feat-nav-item').each(function () {
      var $i = $(this);
      $i.toggle(!q || $i.text().toLowerCase().indexOf(q) >= 0);
    });
    $('.feat-nav-group').each(function () { $(this).toggle($(this).find('.feat-nav-item:visible').length > 0); });
  });
  $(window).on('resize', function () { Object.keys(charts).forEach(function (k) { charts[k].resize(); }); });

  loadAll();
  var $active = $('.feat-nav-item.active');
  if ($active.length) $active[0].scrollIntoView({ block: 'nearest' });
});
