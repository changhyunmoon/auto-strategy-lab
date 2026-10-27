/* 시각화 화면: 차트 · 상관 (변수 하나의 분포 · 관계는 변수 탐색 화면) */
$(function () {
  var $page = $('#vizPage');
  if (!$page.length) return;

  var datasetId = $page.data('id');
  var columns = String($page.data('columns') || '').split(',').filter(Boolean);
  var features = String($page.data('features') || '').split(',').filter(Boolean);
  function has(c) { return columns.indexOf(c) >= 0; }

  $('#datasetSel').on('change', function () { location.href = '/viz?datasetId=' + this.value; });
  function assetId() { return $('input[name="vAsset"]:checked').val(); }
  function assetCode() { return $('input[name="vAsset"]:checked').data('code'); }
  function interval() { return $('input[name="vInterval"]:checked').val(); }

  /* ================= 차트 탭 ================= */

  // 가격 위 겹치기: 데이터세트의 괴리 열로 원래 선을 다시 계산한다
  var OVERLAYS = [
    { id: 'ema20', name: 'EMA 20', needs: ['ema_dev_20'], lines: function (s, close) { return [{ name: 'EMA 20', data: fromDev(close, s.ema_dev_20) }]; } },
    { id: 'ema60', name: 'EMA 60', needs: ['ema_dev_60'], lines: function (s, close) { return [{ name: 'EMA 60', data: fromDev(close, s.ema_dev_60) }]; } },
    { id: 'vwap', name: 'VWAP 60', needs: ['vwap_dev_60'], lines: function (s, close) { return [{ name: 'VWAP 60', data: fromDev(close, s.vwap_dev_60) }]; } },
    { id: 'bb', name: '볼린저밴드 (20, 2)', needs: ['bb_pctb_20', 'bb_width_20'], lines: function (s, close) {
        var up = [], lo = [];
        close.forEach(function (c, i) {
          var p = s.bb_pctb_20[i], w = s.bb_width_20[i];
          if (p == null || w == null) { up.push(null); lo.push(null); return; }
          var mid = c / (1 + w * (p - 0.5));
          up.push(mid * (1 + w / 2)); lo.push(mid * (1 - w / 2));
        });
        return [{ name: 'BB 상단', data: up, dashed: true }, { name: 'BB 하단', data: lo, dashed: true }];
      } }
  ].filter(function (o) { return o.needs.every(has); });

  // 아래 패널: 미리 묶어 둔 것 + 나머지 지표 열 하나씩
  var PRESETS = [
    { id: 'rsi', name: 'RSI 14', cols: ['rsi_14'], scale: 100, marks: [30, 70] },
    { id: 'macd', name: 'MACD (12, 26, 9)', cols: ['macd_line', 'macd_signal', 'macd_hist'], bars: ['macd_hist'] },
    { id: 'buy', name: '매수 주도 비율', cols: ['taker_buy_ratio'], marks: [0.5] },
    { id: 'volratio', name: '거래량 비율 (20)', cols: ['volume_ratio_20'], bars: ['volume_ratio_20'] }
  ].map(function (p) { p.cols = p.cols.filter(has); return p; }).filter(function (p) { return p.cols.length; });
  var presetCols = [].concat.apply([], PRESETS.map(function (p) { return p.cols; }));
  var PANELS = PRESETS.concat(features.filter(function (c) { return presetCols.indexOf(c) < 0; })
    .map(function (c) { return { id: 'col:' + c, name: c, cols: [c], single: true }; }));

  var byId = {};
  OVERLAYS.forEach(function (o) { o.type = 'overlay'; byId[o.id] = o; });
  PANELS.forEach(function (p) { p.type = 'panel'; byId[p.id] = p; });

  var active = ['ema20', 'ema60', 'rsi', 'macd'].filter(function (id) { return byId[id]; });
  var range = null;       // 확대해서 다시 불러온 구간 {from, to}. null 이면 전체
  var data = null;
  var chart = echarts.init(document.getElementById('mainChart'), 'clean');
  var zoomTimer = null;

  function fromDev(close, dev) {
    return close.map(function (c, i) { return dev[i] == null ? null : c / (1 + dev[i]); });
  }

  function neededColumns() {
    var cols = [];
    active.forEach(function (id) {
      var d = byId[id];
      (d.type === 'overlay' ? d.needs : d.cols).forEach(function (c) { if (cols.indexOf(c) < 0) cols.push(c); });
    });
    return cols;
  }

  function loadSeries() {
    var params = { assetId: assetId(), interval: interval() };
    if (range) { params.from = range.from; params.to = range.to; }
    var cols = neededColumns();
    if (cols.length) params.columns = cols.join(',');
    chart.showLoading('default', { text: '불러오는 중', color: '#2383e2', maskColor: 'rgba(255,255,255,.6)' });
    App.api('GET', '/api/viz/' + datasetId + '/series', params).done(function (res) {
      data = res;
      chart.hideLoading();
      render();
    }).fail(function () { chart.hideLoading(); });
  }

  function round(x) { return x == null ? null : Math.round(x * 1e6) / 1e6; }

  function render() {
    if (!data) return;
    var panels = active.filter(function (id) { return byId[id].type === 'panel'; });
    var top = 30, mainH = 300, volH = 60, panelH = 110, gap = 28;
    var grids = [{ left: 80, right: 24, top: top, height: mainH }, { left: 80, right: 24, top: top + mainH + 8, height: volH }];
    panels.forEach(function (_, i) { grids.push({ left: 80, right: 24, top: top + mainH + 8 + volH + gap + i * (panelH + gap), height: panelH }); });
    var last = grids[grids.length - 1];
    $('#mainChart').css('height', (last.top + last.height + 60) + 'px');
    chart.resize();

    var idx = grids.map(function (_, i) { return i; });
    var xAxes = idx.map(function (i) {
      return { type: 'category', data: data.times, gridIndex: i, boundaryGap: true,
        axisLabel: { show: i === grids.length - 1 }, axisPointer: { label: { show: i === grids.length - 1 } } };
    });
    var yAxes = idx.map(function (i) { return { scale: true, gridIndex: i, splitNumber: i === 0 ? 5 : 2 }; });

    var close = data.candles.map(function (c) { return c[1]; });
    var series = [
      { name: assetCode(), type: 'candlestick', data: data.candles, xAxisIndex: 0, yAxisIndex: 0 },
      { name: '거래량', type: 'bar', xAxisIndex: 1, yAxisIndex: 1, data: data.volume.map(function (v, i) {
          var c = data.candles[i];
          return { value: round(v), itemStyle: { color: c[1] >= c[0] ? 'rgba(224,62,62,.45)' : 'rgba(35,131,226,.45)' } };
        }) }
    ];
    active.forEach(function (id) {
      var d = byId[id];
      if (d.type === 'overlay') {
        d.lines(data.series, close).forEach(function (l) {
          series.push({ name: l.name, type: 'line', xAxisIndex: 0, yAxisIndex: 0, data: l.data.map(round), showSymbol: false,
            lineStyle: { width: 1.3, type: l.dashed ? 'dashed' : 'solid' } });
        });
      } else {
        var gi = 2 + panels.indexOf(id);
        d.cols.forEach(function (c) {
          var values = (data.series[c] || []).map(function (v) { return v == null ? null : round(v * (d.scale || 1)); });
          series.push({ name: c, type: (d.bars || []).indexOf(c) >= 0 ? 'bar' : 'line', xAxisIndex: gi, yAxisIndex: gi,
            data: values, showSymbol: false, lineStyle: { width: 1.3 },
            markLine: d.marks ? { silent: true, symbol: 'none', label: { show: false },
              lineStyle: { color: '#d3d3cf', type: 'dashed' }, data: d.marks.map(function (y) { return { yAxis: y }; }) } : undefined });
        });
      }
    });
    var titles = panels.map(function (id, i) {
      return { text: byId[id].name, left: 80, top: grids[2 + i].top - 20, textStyle: { fontSize: 12, fontWeight: 500, color: '#787774' } };
    });

    chart.setOption({
      animation: false,
      title: titles,
      legend: { top: 0, left: 80, data: series.filter(function (s) { return s.xAxisIndex === 0; }).map(function (s) { return s.name; }) },
      tooltip: { trigger: 'axis', axisPointer: { type: 'cross' } },
      axisPointer: { link: [{ xAxisIndex: 'all' }] },
      grid: grids, xAxis: xAxes, yAxis: yAxes,
      dataZoom: [{ type: 'inside', xAxisIndex: idx, start: 0, end: 100 }, { type: 'slider', xAxisIndex: idx, bottom: 10, height: 22, start: 0, end: 100 }],
      series: series
    }, true);

    $('#bucketText').text(App.formatNumber(data.totalBars) + '봉 · ' + data.bucketText);
    $('#resetZoom').toggle(!!range);
    renderChips();
  }

  function renderChips() {
    $('#indicatorChips > .tag').remove();
    active.forEach(function (id) {
      $('<span class="tag tag-blue"></span>').text(byId[id].name).append(' <span class="x">✕</span>')
        .attr('data-id', id).insertBefore('#addMenu');
    });
    var $m = $('#addMenuList').empty();
    [['overlay', '가격 위에 겹치기'], ['panel', '아래 패널로 추가']].forEach(function (g) {
      var items = OVERLAYS.concat(PANELS).filter(function (d) { return d.type === g[0] && active.indexOf(d.id) < 0; });
      if (!items.length) return;
      $m.append($('<div class="menu-group"></div>').text(g[1]));
      items.forEach(function (d) {
        $m.append($('<div class="menu-item"></div>').attr('data-id', d.id).text(d.name)
          .append($('<span class="where"></span>').text(d.type === 'overlay' ? '겹치기' : d.single ? '열' : '묶음')));
      });
    });
  }

  // 확대하면 그 구간을 원래 간격으로 다시 불러온다 (묶어서 보여주는 중일 때만)
  chart.on('datazoom', function () {
    clearTimeout(zoomTimer);
    zoomTimer = setTimeout(function () {
      if (!data || data.bucketBars <= 1) return;
      var dz = chart.getOption().dataZoom[0];
      if (dz.start <= 0.5 && dz.end >= 99.5) return;
      var n = data.timestamps.length;
      var s = Math.max(0, Math.floor(dz.start / 100 * n)), e = Math.min(n - 1, Math.ceil(dz.end / 100 * n));
      range = { from: data.timestamps[s], to: e + 1 < n ? data.timestamps[e + 1] : data.to };
      loadSeries();
    }, 500);
  });

  $('#resetZoom').on('click', function () { range = null; loadSeries(); });
  $('#indicatorChips').on('click', '.x', function () {
    var id = $(this).parent().data('id');
    active = active.filter(function (a) { return a !== id; });
    loadSeries();
  });
  $('#addMenu > button').on('click', function (e) { e.stopPropagation(); $('#addMenu').toggleClass('open'); });
  $('#addMenuList').on('click', '.menu-item', function () {
    active.push($(this).data('id'));
    $('#addMenu').removeClass('open');
    loadSeries();
  });
  $(document).on('click', function () { $('#addMenu').removeClass('open'); });

  /* ================= 상관 ================= */

  var charts = {};
  function getChart(id) {
    if (!charts[id]) charts[id] = echarts.init(document.getElementById(id), 'clean');
    return charts[id];
  }
  function base() { return { assetId: assetId(), interval: interval() }; }


  function renderCorr() {
    if (!features.length) return;
    getChart('corrChart').showLoading('default', { text: '계산 중', color: '#2383e2', maskColor: 'rgba(255,255,255,.6)' });
    App.api('GET', '/api/viz/' + datasetId + '/correlation', base()).done(function (r) {
      var cells = [];
      r.matrix.forEach(function (row, i) { row.forEach(function (v, j) { cells.push([j, i, v]); }); });
      var many = r.columns.length > 20;
      var chartEl = getChart('corrChart');
      chartEl.hideLoading();
      chartEl.setOption({
        tooltip: { formatter: function (p) { return r.columns[p.value[1]] + ' × ' + r.columns[p.value[0]] + '<br><b>' + (p.value[2] == null ? '-' : p.value[2]) + '</b>'; } },
        grid: { left: 150, right: 80, top: 10, bottom: 140 },
        xAxis: { type: 'category', data: r.columns, axisLabel: { rotate: 50, fontSize: 10 } },
        yAxis: { type: 'category', data: r.columns, axisLabel: { fontSize: 10 } },
        visualMap: { min: -1, max: 1, calculable: true, orient: 'vertical', right: 0, top: 'center', itemHeight: 220,
          inRange: { color: ['#2383e2', '#f7f7f5', '#e03e3e'] } },
        series: [{ type: 'heatmap', data: cells, label: { show: !many, fontSize: 10, color: '#37352f' }, itemStyle: { borderColor: '#fff', borderWidth: 1 } }]
      }, true);
    });
  }

  var current = 'chart';
  function refreshTab(name) {
    if (name === 'chart') chart.resize();
    if (name === 'corr') renderCorr();
    Object.keys(charts).forEach(function (k) { charts[k].resize(); });
  }
  $(document).on('tab:shown', function (_, name) { current = name; refreshTab(name); });
  $('input[name="vAsset"], input[name="vInterval"]').on('change', function () {
    range = null;
    loadSeries();
    if (current !== 'chart') refreshTab(current);
  });
  $(window).on('resize', function () { chart.resize(); Object.keys(charts).forEach(function (k) { charts[k].resize(); }); });

  loadSeries();
});
