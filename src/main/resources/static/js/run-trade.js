/* 실험 상세 · 💰 거래 성과 탭: 규칙 · 비용을 정해 백테스트를 계산하고 그린다 */
$(function () {
  var $panel = $('#tradePanel');
  if (!$panel.length) return;
  var $page = $('#runPage');
  var id = $page.data('id');
  var charts = [];
  var loaded = false;

  $('#btHold').val($panel.data('horizon') || 1);
  if (String($panel.data('proba')) !== 'true') {
    $('#btProbaChip').addClass('is-disabled').attr('title', '이 모델은 확률을 내지 않아 예측 클래스로만 계산합니다.');
  }
  function testDone() { return $page.attr('data-test-status') === 'DONE'; }
  function syncTestChip() {
    $('#btTestChip').toggleClass('is-disabled', !testDone()).attr('title', testDone() ? '' : '최종 테스트를 먼저 실행하세요.');
  }
  syncTestChip();
  $(document).on('test:done', function () { $page.attr('data-test-status', 'DONE'); syncTestChip(); });

  $('input[name="btRule"]').on('change', function () { $('.bt-proba').toggle(this.value === 'proba'); });
  $('input[name="btOrder"]').on('change', function () { $('.bt-limit').toggle(this.value === 'LIMIT'); });
  function isLimit() { return $('input[name="btOrder"]:checked').val() === 'LIMIT'; }

  function pctText(v, digits) {
    if (v == null || isNaN(v)) return '–';
    return (v >= 0 ? '+' : '') + Number(v).toFixed(digits == null ? 2 : digits) + '%';
  }

  function card(term, label, value, sub, cls) {
    var $c = $('<div class="card-stat"></div>');
    $c.append($('<div class="label"></div>').append($('<span></span>').attr('data-term', term).text(label)));
    $c.append($('<div class="value"></div>').text(value).addClass(cls || ''));
    if (sub) $c.append($('<div class="sub"></div>').text(sub));
    return $c;
  }

  function section(title, term, sub) {
    var $s = $('<div class="result-section"></div>');
    var $h = $('<h3></h3>').append($('<span></span>').attr('data-term', term || null).text(title));
    if (sub) $h.append($('<span class="count"></span>').text(sub));
    return $s.append($h);
  }

  function chart(el, option) {
    var c = echarts.init(el, 'clean');
    c.setOption(option);
    charts.push(c);
    return c;
  }

  function run() {
    var body = {
      split: $('input[name="btSplit"]:checked').val(),
      feePct: Number($('#btFee').val()),
      slippagePct: Number($('#btSlip').val()),
      useProbability: $('input[name="btRule"]:checked').val() === 'proba',
      upThreshold: Number($('#btUp').val()),
      downThreshold: Number($('#btDown').val()),
      holdBars: Number($('#btHold').val()),
      extendOnSignal: $('#btExtend').is(':checked'),
      orderType: isLimit() ? 'LIMIT' : 'MARKET',
      limitWaitBars: Number($('#btWait').val()) || 1
    };
    $('#btRun').prop('disabled', true).text('계산 중…');
    App.api('POST', '/api/runs/' + id + '/backtest', body).done(render).always(function () {
      $('#btRun').prop('disabled', false).text('계산');
    });
  }

  function render(r) {
    charts.splice(0).forEach(function (c) { c.dispose(); });
    var $box = $('#btResult').empty();
    var t = r.total;
    var limit = r.settings.orderType === 'LIMIT';
    var cost = limit ? r.settings.feePct * 2 : (r.settings.feePct + r.settings.slippagePct) * 2;
    var orderText = limit ? '지정가 (' + r.settings.limitWaitBars + '봉 대기)' : '시장가';

    // ---------- 요약 ----------
    var $s = section('요약', null, (r.split === 'test' ? '최종 테스트' : '검증') + ' 구간 · ' + orderText + ' · ' + r.horizon + '봉 보유 기준 · 왕복 비용 ' + cost.toFixed(2) + '%'
      + (limit ? ' (둘 다 지정가로 체결될 때)' : '')).appendTo($box);
    var $cards = $('<div class="cards"></div>').appendTo($s);
    var vsHold = t.netReturnPct - t.buyHoldPct;
    $cards.append(card('netReturn', '순수익률', pctText(t.netReturnPct), '단순 보유 대비 ' + pctText(vsHold).replace('%', '%p'), t.netReturnPct >= 0 ? 'diff-up' : 'diff-down'));
    $cards.append(card('grossReturn', '비용 전 수익률', pctText(t.grossReturnPct), '비용이 ' + (t.grossReturnPct - t.netReturnPct).toFixed(2) + '%p 차감'));
    $cards.append(card('buyHold', '단순 보유', pctText(t.buyHoldPct), '자산별 평균'));
    $cards.append(card('mdd', '최대 낙폭', t.maxDrawdownPct == null ? '–' : '−' + t.maxDrawdownPct.toFixed(2) + '%'));
    $cards.append(card('trades', '거래 수', App.formatNumber(t.trades), '롱 ' + t.longTrades + ' · 숏 ' + t.shortTrades));
    $cards.append(card('winRate', '승률', App.pct(t.winRate), '비용 차감 후 이익 거래'));
    $cards.append(card('avgTrade', '평균 거래 수익', pctText(t.avgTradeNetPct, 3), '평균 보유 ' + App.fixed(t.avgHoldBars, 1) + '봉'));
    $cards.append(card('exposure', '보유 비율', App.pct(t.exposure), '샤프 ' + App.fixed(t.sharpe, 2) + ' (참고)'));
    if (limit && t.orders) {
      var o = t.orders;
      $cards.append(card('limitFill', '진입 체결률', App.pct(o.entryFillRate), '주문 ' + App.formatNumber(o.entryOrders) + ' · 놓침 ' + App.formatNumber(o.entryMissed)));
      $cards.append(card('limitFallback', '청산 지정가 체결', App.pct(o.exitFillRate), '시장가로 전환 ' + App.formatNumber(o.exitFallback) + '건'));
    }
    $s.append(help([
      '<b>순수익률</b>은 수수료 · 슬리피지를 뺀 누적 수익률입니다. 같은 기간 <b>단순 보유</b>(처음에 사서 끝까지)보다 나은지 함께 보세요.',
      '<b>비용 전 수익률</b>과의 차이는 거래 비용이 얼마나 갉아먹었는지입니다. 거래가 잦을수록 커집니다.',
      '롱 합계 ' + pctText(t.longNetPct) + ' · 숏 합계 ' + pctText(t.shortNetPct) + ' (거래별 순수익률의 단순 합). 숏은 현물에서 할 수 없으므로 참고용입니다.',
      '거래가 수십 번 이하면 수익률은 운에 크게 좌우됩니다.'
    ]));

    // ---------- 자산 곡선 ----------
    var $c = section('누적 수익 곡선', 'netReturn', '자금을 자산 수로 똑같이 나눈 합산 · 보유 중 평가손익 포함').appendTo($box);
    var el = $('<div class="chart chart--md"></div>').appendTo($c)[0];
    var cv = r.curve;
    chart(el, {
      grid: [{ left: 60, right: 30, top: 36, height: '58%' }, { left: 60, right: 30, top: '74%', bottom: 40 }],
      legend: { top: 0 },
      tooltip: { trigger: 'axis', valueFormatter: function (v) { return v == null ? '–' : Number(v).toFixed(2) + '%'; } },
      axisPointer: { link: [{ xAxisIndex: 'all' }] },
      xAxis: [
        { type: 'time', gridIndex: 0, axisLabel: { show: false } },
        { type: 'time', gridIndex: 1 }
      ],
      yAxis: [
        { type: 'value', gridIndex: 0, name: '%', scale: true },
        { type: 'value', gridIndex: 1, name: '낙폭 %', max: 0 }
      ],
      dataZoom: [{ type: 'inside', xAxisIndex: [0, 1] }],
      series: [
        { name: '순수익', type: 'line', showSymbol: false, data: cv.time.map(function (x, i) { return [x, cv.netPct[i]]; }), lineStyle: { width: 2 } },
        { name: '비용 전', type: 'line', showSymbol: false, data: cv.time.map(function (x, i) { return [x, cv.grossPct[i]]; }), lineStyle: { type: 'dashed', width: 1 }, itemStyle: { color: '#b9b9b5' } },
        { name: '낙폭', type: 'line', xAxisIndex: 1, yAxisIndex: 1, showSymbol: false, data: cv.time.map(function (x, i) { return [x, cv.drawdownPct[i]]; }),
          areaStyle: { opacity: 0.25 }, itemStyle: { color: '#2383e2' }, lineStyle: { width: 1 } }
      ]
    });
    $c.append(help(['위: 시작 자금 대비 누적 수익률(%). 회색 점선은 비용이 없다고 할 때입니다. 아래: 직전 최고점 대비 얼마나 떨어져 있는지(낙폭). 가장 깊은 곳이 최대 낙폭입니다. 시각은 마우스를 올리면 보입니다 (UTC 기준 묶음).']));

    // ---------- 주문 방식 비교 ----------
    renderCompare(r, cv, $box);

    // ---------- 자산별 ----------
    var $a = section('자산별').appendTo($box);
    var $t = $('<table class="db-table db-table--compact"><thead><tr><th>자산</th><th class="num">순수익</th><th class="num">비용 전</th><th class="num">단순 보유</th><th class="num">최대 낙폭</th><th class="num">거래</th><th class="num">승률</th><th class="num">평균 거래</th></tr></thead><tbody></tbody></table>');
    Object.keys(r.byAsset || {}).forEach(function (k) {
      var x = r.byAsset[k];
      $t.find('tbody').append($('<tr></tr>').append($('<td></td>').text(k))
        .append($('<td class="num"></td>').text(pctText(x.netReturnPct)).addClass(x.netReturnPct >= 0 ? 'diff-up' : 'diff-down'))
        .append($('<td class="num"></td>').text(pctText(x.grossReturnPct)))
        .append($('<td class="num"></td>').text(pctText(x.buyHoldPct)))
        .append($('<td class="num"></td>').text(x.maxDrawdownPct.toFixed(2) + '%'))
        .append($('<td class="num"></td>').text(App.formatNumber(x.trades)))
        .append($('<td class="num"></td>').text(App.pct(x.winRate)))
        .append($('<td class="num"></td>').text(pctText(x.avgTradeNetPct, 3))));
    });
    $('<div class="db-table-wrap"></div>').append($t).appendTo($a);

    // ---------- 월별 ----------
    if (r.monthly && r.monthly.length) {
      var $m = section('월별 수익률', null, 'UTC 기준 월말 평가').appendTo($box);
      var mEl = $('<div class="chart chart--sm"></div>').appendTo($m)[0];
      chart(mEl, {
        grid: { left: 60, right: 20, top: 20, bottom: 30 },
        tooltip: { trigger: 'axis', valueFormatter: function (v) { return Number(v).toFixed(2) + '%'; } },
        xAxis: { type: 'category', data: r.monthly.map(function (x) { return x.period; }) },
        yAxis: { type: 'value', axisLabel: { formatter: '{value}%' } },
        series: [{ type: 'bar', barMaxWidth: 30, data: r.monthly.map(function (x) { return { value: x.returnPct, itemStyle: { color: x.returnPct >= 0 ? '#e03e3e' : '#2383e2' } }; }) }]
      });
      $m.append(help(['특정 달에만 수익이 몰려 있으면 그 시기의 시장 상황에 기댄 결과일 수 있습니다.']));
    }

    // ---------- 확률 기준별 ----------
    if (r.sweep) {
      var $w = section('확률 기준별 비교', 'sweep', '같은 비용 · 보유 규칙, 상승 · 하락 확률 기준만 바꿈').appendTo($box);
      var wEl = $('<div class="chart chart--sm"></div>').appendTo($w)[0];
      chart(wEl, {
        grid: { left: 60, right: 60, top: 36, bottom: 40 },
        legend: { top: 0 },
        tooltip: { trigger: 'axis' },
        xAxis: { type: 'category', name: '확률 ≥', nameLocation: 'middle', nameGap: 26, data: r.sweep.map(function (x) { return x.threshold.toFixed(2); }) },
        yAxis: [{ type: 'value', name: '수익 %' }, { type: 'value', name: '거래 수', splitLine: { show: false } }],
        series: [
          { name: '거래 수', type: 'bar', yAxisIndex: 1, data: r.sweep.map(function (x) { return x.trades; }), itemStyle: { color: '#ebebe9' }, barMaxWidth: 26 },
          { name: '순수익 %', type: 'line', data: r.sweep.map(function (x) { return Number(x.netReturnPct.toFixed(3)); }), symbolSize: 6 },
          { name: '비용 전 %', type: 'line', data: r.sweep.map(function (x) { return Number(x.grossReturnPct.toFixed(3)); }), symbolSize: 4, lineStyle: { type: 'dashed' } },
          { name: '최대 낙폭 %', type: 'line', data: r.sweep.map(function (x) { return -Number(x.maxDrawdownPct.toFixed(3)); }), symbolSize: 4, lineStyle: { type: 'dotted' } }
        ]
      });
      $w.append(help([
        '기준을 높이면 신호(거래)가 줄고, 보통 한 번당 정확도는 오르지만 기회도 줄어듭니다. 비용 전과 순수익의 차이가 거래 비용입니다.',
        '<b>주의:</b> 이 표에서 가장 좋은 기준을 고르면 그 자체가 검증 구간에 맞춘 선택입니다. 고른 기준은 최종 테스트로 다시 확인하세요.'
      ]));
    }

    // ---------- 거래 목록 ----------
    var $l = section('거래 목록', 'trades', '마지막 ' + r.tradesShown + '건 · 시각 KST').appendTo($box);
    var $tt = $('<table class="db-table db-table--compact"><thead><tr><th>자산</th><th>방향</th><th>진입</th><th>청산</th><th class="num">진입가</th><th class="num">청산가</th><th class="num">보유 봉</th><th class="num">비용 전</th><th class="num">순수익</th><th>주문 (진입 → 청산)</th><th>청산 사유</th></tr></thead><tbody></tbody></table>');
    r.trades.slice().reverse().forEach(function (x) {
      $tt.find('tbody').append($('<tr></tr>').append($('<td></td>').text(x.asset))
        .append($('<td></td>').append($('<span class="tag"></span>').addClass(x.side === '롱' ? 'tag-red' : 'tag-blue').text(x.side)))
        .append($('<td class="num"></td>').text(App.msToKst(x.entryTime))).append($('<td class="num"></td>').text(App.msToKst(x.exitTime)))
        .append($('<td class="num"></td>').text(App.formatNumber(x.entry))).append($('<td class="num"></td>').text(App.formatNumber(x.exit)))
        .append($('<td class="num"></td>').text(x.bars))
        .append($('<td class="num"></td>').text(pctText(x.grossPct, 3)))
        .append($('<td class="num"></td>').text(pctText(x.netPct, 3)).addClass(x.netPct >= 0 ? 'diff-up' : 'diff-down'))
        .append($('<td class="small"></td>').text((x.entryOrder || '시장가') + ' → ' + (x.exitOrder || '시장가'))
          .toggleClass('diff-down', x.entryOrder === '지정가' && x.exitOrder === '시장가'))
        .append($('<td class="small muted"></td>').text(x.reason)));
    });
    if (!r.trades.length) $l.append('<div class="small faint">거래가 없습니다. 확률 기준을 낮추거나 예측 클래스 규칙을 써 보세요.</div>');
    else $('<div class="db-table-wrap" style="max-height:420px;overflow-y:auto"></div>').append($tt).appendTo($l);
  }

  /* 같은 신호 · 보유 규칙에서 주문 방식만 바꾼 비교: 시장가(슬리피지 포함) · 시장가(슬리피지 0) · 지정가 */
  function renderCompare(r, cv, $box) {
    var list = r.compare || [];
    var $p = section('주문 방식 비교', 'orderCompare', '같은 예측 · 같은 보유 규칙, 주문 방식과 슬리피지만 바꿈').appendTo($box);
    if (!r.limitAvailable) {
      $p.append($('<div class="callout callout-yellow small" style="margin:6px 0 10px"></div>')
        .append('<div class="callout-icon">ℹ️</div>')
        .append($('<div class="callout-body"></div>').text('이 학습 데이터에는 봉의 고가 · 저가가 저장되어 있지 않아 지정가는 비교에서 빠졌습니다. 학습 데이터를 「같은 설정으로 새로 만들기」로 다시 만들고 실험을 다시 실행하면 지정가도 함께 나옵니다.')));
    }
    var colors = { market: '#2383e2', marketNoSlip: '#0f7b6c', limit: '#d9730d' };
    var el = $('<div class="chart chart--md"></div>').appendTo($p)[0];
    var series = list.map(function (c) {
      return { name: c.label + (c.selected ? ' (선택)' : ''), type: 'line', showSymbol: false,
        data: cv.time.map(function (x, i) { return [x, c.netPct[i]]; }),
        lineStyle: { width: c.selected ? 2.4 : 1.4 }, itemStyle: { color: colors[c.key] } };
    });
    series.push({ name: '비용 없음 (선택한 방식)', type: 'line', showSymbol: false, data: cv.time.map(function (x, i) { return [x, cv.grossPct[i]]; }),
      lineStyle: { type: 'dashed', width: 1 }, itemStyle: { color: '#b9b9b5' } });
    chart(el, {
      grid: { left: 60, right: 30, top: 40, bottom: 40 },
      legend: { top: 0 },
      tooltip: { trigger: 'axis', valueFormatter: function (v) { return v == null ? '–' : Number(v).toFixed(2) + '%'; } },
      xAxis: { type: 'time' },
      yAxis: { type: 'value', name: '%', scale: true },
      dataZoom: [{ type: 'inside' }],
      series: series
    });

    var $t = $('<table class="db-table db-table--compact"><thead><tr><th>방식</th><th class="num">왕복 비용</th><th class="num">순수익</th><th class="num">비용 전</th><th class="num">거래</th><th class="num">승률</th><th class="num">평균 거래</th><th class="num">최대 낙폭</th><th class="num">진입 체결률</th><th class="num">청산 시장가 전환</th></tr></thead><tbody></tbody></table>');
    list.forEach(function (c) {
      var o = c.orders;
      $t.find('tbody').append($('<tr></tr>').toggleClass('row-selected', !!c.selected)
        .append($('<td></td>').append($('<span class="dot-mark"></span>').css('background', colors[c.key])).append(document.createTextNode(' ' + c.label + (c.selected ? ' · 선택' : ''))))
        .append($('<td class="num"></td>').text(c.roundTripCostPct.toFixed(2) + '%' + (c.key === 'limit' ? '*' : '')))
        .append($('<td class="num"></td>').text(pctText(c.netReturnPct)).addClass(c.netReturnPct >= 0 ? 'diff-up' : 'diff-down'))
        .append($('<td class="num"></td>').text(pctText(c.grossReturnPct)))
        .append($('<td class="num"></td>').text(App.formatNumber(c.trades)))
        .append($('<td class="num"></td>').text(App.pct(c.winRate)))
        .append($('<td class="num"></td>').text(pctText(c.avgTradeNetPct, 3)))
        .append($('<td class="num"></td>').text(c.maxDrawdownPct == null ? '–' : '−' + c.maxDrawdownPct.toFixed(2) + '%'))
        .append($('<td class="num"></td>').text(o ? App.pct(o.entryFillRate) + ' (놓침 ' + App.formatNumber(o.entryMissed) + ')' : '100% (바로 체결)'))
        .append($('<td class="num"></td>').text(o ? App.formatNumber(o.exitFallback) + '건' : '–')));
    });
    $('<div class="db-table-wrap"></div>').append($t).appendTo($p);

    var lines = [];
    var m = byKey(list, 'market'), ns = byKey(list, 'marketNoSlip'), lm = byKey(list, 'limit');
    if (m && ns) {
      lines.push('<b>슬리피지 영향</b>: 시장가에서 슬리피지 ' + r.settings.slippagePct + '% 를 넣으면 순수익이 ' + pctText(ns.netReturnPct) + ' → ' + pctText(m.netReturnPct)
        + ' 로 ' + Math.abs(ns.netReturnPct - m.netReturnPct).toFixed(2) + '%p 줄어듭니다. 거래 ' + App.formatNumber(m.trades) + '번 × 왕복 ' + (r.settings.slippagePct * 2).toFixed(2) + '% 만큼입니다.');
    }
    if (m && lm) {
      var diff = lm.netReturnPct - m.netReturnPct;
      lines.push('<b>지정가 vs 시장가</b>: 지정가 순수익 ' + pctText(lm.netReturnPct) + ' 은 시장가보다 ' + (diff >= 0 ? '+' : '') + diff.toFixed(2) + '%p ' + (diff >= 0 ? '높습니다' : '낮습니다') + '. '
        + '지정가는 슬리피지가 없는 대신 진입 주문 ' + App.formatNumber(lm.orders.entryOrders) + '건 중 ' + App.formatNumber(lm.orders.entryMissed) + '건을 놓쳤습니다.'
        + (diff < 0 ? ' 가격이 바로 오르는 좋은 신호일수록 지정가에 안 걸리고 지나가 버리기 쉬워서, 놓친 거래가 수익 거래였을 가능성이 큽니다.' : ''));
    }
    lines.push('<b>지정가 체결 가정</b>: 판단 봉 종가에 주문을 걸고, 다음 봉부터 정한 봉 수 안에 가격이 주문가를 <b>넘어서야</b> (매수는 저가 &lt; 주문가, 매도는 고가 &gt; 주문가) 주문가에 체결된다고 봅니다. 딱 닿기만 한 경우는 앞선 주문 때문에 안 될 수 있어 미체결로 봅니다.');
    lines.push('<b>미체결 처리</b>: 진입이 안 되면 주문을 취소하고 그 거래는 하지 않습니다. 청산이 안 되면 기다린 마지막 봉 종가에 시장가로 팝니다 (그때는 슬리피지 포함). 그래서 * 지정가의 실제 왕복 비용은 수수료 × 2 보다 클 수 있습니다.');
    lines.push('<b>주의</b>: 봉 단위로 본 근사입니다. 실제 호가 대기열 · 부분 체결 · 주문 지연은 재현하지 않습니다. 실제 자동 매매 전에 소액으로 체결률을 확인하세요.');
    $p.append(help(lines));
  }
  function byKey(list, key) { return list.filter(function (c) { return c.key === key; })[0]; }

  function help(lines) {
    var $d = $('<details class="help"><summary>ⓘ 읽는 법</summary><div class="help-body"></div></details>');
    lines.forEach(function (l) { $d.find('.help-body').append($('<p></p>').html(l)); });
    return $d;
  }

  $('#btRun').on('click', run);
  $(document).on('tab:shown', function (e, name) {
    if (name !== 'trade') return;
    if (!loaded) { loaded = true; run(); }
    charts.forEach(function (c) { c.resize(); });
  });
  $(window).on('resize', function () { charts.forEach(function (c) { c.resize(); }); });
});
