/* 정답 분포 표 그리기 (정답 만들기 · 정답 상세 · 분할에서 함께 씀) */
var LabelView = (function ($) {
  var ORDER = ['TARGET', 'STOP', 'TIMEOUT', 'AMBIGUOUS', 'GAP', 'NO_FUTURE'];
  var NAME = {
    TARGET: '목표 먼저', STOP: '손실 먼저', TIMEOUT: '시간 만료', AMBIGUOUS: '동시 도달',
    GAP: '빈 1분', NO_FUTURE: '미래 부족', HIDDEN: '판정됨 (숨김)'
  };

  function bar(counts, keys, total) {
    var $b = $('<div class="dist-bar"></div>');
    keys.forEach(function (k) {
      var c = counts[k] || 0;
      if (c > 0) $('<span></span>').addClass('c-' + k).css('width', (100 * c / total) + '%').attr('title', NAME[k] + ' ' + App.ratio(c, total)).appendTo($b);
    });
    return $b;
  }

  function legend() {
    var $l = $('<div class="small muted" style="display:flex; flex-wrap:wrap; gap:4px 14px; margin:6px 0"></div>');
    ORDER.forEach(function (k) {
      $l.append($('<span></span>').attr('data-hover-term', 'out_' + k).append($('<span class="legend-dot"></span>').addClass('c-' + k)).append(document.createTextNode(NAME[k])));
    });
    return $l;
  }

  /** summary = LabelSummary JSON. 탐색 기간 표 + 숨김 기간 표 + 경고 */
  function render($box, s) {
    $box.empty();
    if (!s) return;
    (s.warnings || []).forEach(function (w) {
      $box.append($('<div class="callout callout-yellow" style="margin-bottom:6px; padding:8px 12px"></div>').append('<div class="callout-icon">⚠️</div>')
        .append($('<div class="callout-body small"></div>').text(w)));
    });
    $box.append(legend());

    var $t = $('<table class="db-table"><thead><tr><th>거래쌍</th><th class="num">탐색 행</th><th>분포</th></tr></thead><tbody></tbody></table>');
    ORDER.forEach(function (k) {
      $t.find('thead tr').append($('<th class="num"></th>').append($('<span></span>').attr('data-term', 'out_' + k).text(NAME[k])));
    });
    $t.find('thead tr').append($('<th class="num"></th>').append($('<span data-term="barsToEvent">도달 평균</span>')))
      .append($('<th class="num"></th>').append($('<span data-term="timeoutReturn">만료 평균 수익</span>')));
    s.markets.forEach(function (m) {
      var p = m.explore, $tr = $('<tr></tr>');
      $tr.append($('<td></td>').text(m.label));
      $tr.append($('<td class="num"></td>').text(App.formatNumber(p.rows)));
      $tr.append($('<td></td>').append(bar(p.counts, ORDER, p.rows || 1)));
      ORDER.forEach(function (k) {
        var c = p.counts[k] || 0;
        $tr.append($('<td class="num"></td>').html(App.formatNumber(c) + '<div class="small muted">' + App.ratio(c, p.rows) + '</div>'));
      });
      $tr.append($('<td class="num"></td>').text(p.avgBarsToEvent == null ? '–' : p.avgBarsToEvent.toFixed(1) + '분'));
      $tr.append($('<td class="num"></td>').text(p.avgTimeoutReturn == null ? '–' : p.avgTimeoutReturn.toFixed(3) + '%'));
      $t.find('tbody').append($tr);
    });
    $box.append($('<h3 class="section-title" style="font-size:15px; margin-top:12px">탐색 기간</h3>'))
      .append($('<div class="db-table-wrap"></div>').append($t));

    var $h = $('<table class="db-table db-table--compact"><thead><tr><th>거래쌍</th><th class="num">숨김 행</th>' +
      '<th class="num"><span data-term="out_HIDDEN">판정됨 (숨김)</span></th><th class="num"><span data-term="out_GAP">빈 1분</span></th>' +
      '<th class="num"><span data-term="out_NO_FUTURE">미래 부족</span></th></tr></thead><tbody></tbody></table>');
    s.markets.forEach(function (m) {
      var p = m.hidden;
      $h.find('tbody').append($('<tr></tr>').append($('<td></td>').text(m.label))
        .append($('<td class="num"></td>').text(App.formatNumber(p.rows)))
        .append($('<td class="num"></td>').text(App.formatNumber(p.counts.HIDDEN || 0)))
        .append($('<td class="num"></td>').text(App.formatNumber(p.counts.GAP || 0)))
        .append($('<td class="num"></td>').text(App.formatNumber(p.counts.NO_FUTURE || 0))));
    });
    $box.append($('<h3 class="section-title" style="font-size:15px; margin-top:16px"></h3>').append('<span data-term="hiddenPeriod">숨김 기간</span>')
      .append(' <span class="count">행 수 · 데이터 품질만</span>'))
      .append($('<div class="db-table-wrap"></div>').append($h));
  }

  /** 판정 규칙 글 */
  function ruleText(c) {
    var isLong = c.direction !== 'SHORT';
    var rt = c.costEnabled ? (Number(c.feePct) + Number(c.slippagePct)) * 2 : 0;
    var tp = Number(c.targetPct) + rt, sl = Number(c.stopPct) - rt;
    var ex = 100000000;
    var tgt = isLong ? ex * (1 + tp / 100) : ex * (1 - tp / 100);
    var stp = isLong ? ex * (1 - sl / 100) : ex * (1 + sl / 100);
    var lines = [];
    lines.push('판단 시점 t  = 1분봉 i 가 끝난 순간 (입력 X 는 여기까지만)');
    lines.push('진입 가격    = ' + (c.entry === 'CLOSE' ? '봉 i 의 종가' : '봉 i+1 의 시가 (다음 봉이 1분 뒤에 없으면 판정 불가)'));
    lines.push('관찰 구간    = 봉 i+1 ~ 봉 i+' + c.horizon + '  (1분봉 ' + c.horizon + '개, 진입 봉도 첫 관찰)');
    lines.push('목표가       = 진입가 × (1 ' + (isLong ? '+ ' : '− ') + fmt(tp) + '%)' + (rt ? '   ← P ' + fmt(c.targetPct) + '% + 왕복 비용 ' + fmt(rt) + '%' : ''));
    lines.push('손실 기준가  = 진입가 × (1 ' + (isLong ? '− ' : '+ ') + fmt(sl) + '%)' + (rt ? '   ← L ' + fmt(c.stopPct) + '% − 왕복 비용 ' + fmt(rt) + '%' : ''));
    lines.push('도달 판정    = ' + (isLong ? '고가 ≥ 목표가 → 목표 도달 · 저가 ≤ 손실가 → 손실 도달' : '저가 ≤ 목표가 → 목표 도달 · 고가 ≥ 손실가 → 손실 도달') + ' (앞 봉부터)');
    lines.push('같은 봉 둘 다 = ' + (c.sameBar === 'STOP_FIRST' ? '손실 먼저 (보수적)' : '판정 불가 (학습에서 제외)'));
    lines.push('아무것도 없음 = 시간 만료 (마지막 봉 종가로 수익률 기록)');
    lines.push('빈 1분 · 데이터 끝 = 판정 불가 / 데이터 부족 (시간 만료로 바꾸지 않음)');
    lines.push('label_end_time = 사건이 난 봉(또는 봉 i+' + c.horizon + ')이 끝나는 시각');
    lines.push('');
    lines.push('예) 진입가 100,000,000 → 목표가 ' + Math.round(tgt).toLocaleString('ko-KR') + ' · 손실가 ' + Math.round(stp).toLocaleString('ko-KR'));
    return lines.join('\n');
  }

  function fmt(v) {
    return String(Math.round(Number(v) * 10000) / 10000);
  }

  return { render: render, ruleText: ruleText, NAME: NAME, ORDER: ORDER, bar: bar };
})(jQuery);
