/* 분할 요약 그리기 (분할 만들기 · 분할 상세에서 함께 씀) */
var SplitView = (function ($) {
  var SEG = { TRAIN: '학습', VAL: '검증', TEST: '최종 테스트', NONE: '어느 구간에도 없음' };
  var SEG_TERM = { TRAIN: 'trainSplit', VAL: 'valSplit', TEST: 'testSplit' };
  var REASON = {
    OUTSIDE_USE: '사용 기간 밖', UNASSIGNED: '구간 사이', AMBIGUOUS: '동시 도달', GAP: '빈 1분',
    NO_FUTURE: '미래 부족', BOUNDARY: '경계 초과', EMBARGO: '경계 여유'
  };
  var LABELS = ['TARGET', 'STOP', 'TIMEOUT'];

  /** 데이터 기간 위에 세 구간을 그린다 (모두 'YYYY-MM-DD', to 는 포함 날짜) */
  function timeline($el, $from, $to, dataFrom, dataToExclusive, c) {
    $el.empty();
    var start = new Date(dataFrom).getTime(), end = new Date(dataToExclusive).getTime(), span = end - start;
    if (!(span > 0)) return;
    var segs = [['trainFrom', 'trainTo', 'seg-train', '학습'], ['valFrom', 'valTo', 'seg-val', '검증'], ['testFrom', 'testTo', 'seg-test', '테스트']];
    var cursor = start;
    segs.forEach(function (s) {
      if (!c[s[0]] || !c[s[1]]) return;
      var a = Math.max(start, new Date(c[s[0]] + 'T00:00:00Z').getTime());
      var b = Math.min(end, new Date(App.addDays(c[s[1]], 1) + 'T00:00:00Z').getTime());
      if (b <= a) return;
      if (a > cursor) $el.append($('<div class="seg seg-gap"></div>').css('width', (100 * (a - cursor) / span) + '%').attr('title', '어느 구간에도 없음'));
      $el.append($('<div class="seg"></div>').addClass(s[2]).css('width', (100 * (b - a) / span) + '%').text(s[3])
        .attr('title', s[3] + ' ' + c[s[0]] + ' ~ ' + c[s[1]] + ' (' + (App.daysBetween(c[s[0]], c[s[1]]) + 1) + '일)'));
      cursor = Math.max(cursor, b);
    });
    if (cursor < end) $el.append($('<div class="seg seg-gap"></div>').css('width', (100 * (end - cursor) / span) + '%'));
    $from.text('데이터 시작 ' + App.utcDate(dataFrom));
    $to.text('데이터 끝 ' + App.utcLastDate(dataToExclusive));
  }

  /** summary = SplitSummary (TEST.labels 는 null) */
  function render($box, s, testLabels) {
    $box.empty();
    if (!s) return;
    (s.warnings || []).forEach(function (w) {
      $box.append($('<div class="callout callout-yellow" style="margin-bottom:6px; padding:8px 12px"></div>').append('<div class="callout-icon">⚠️</div>')
        .append($('<div class="callout-body small"></div>').text(w)));
    });
    if (s.warnings && s.warnings.length) $box.append('<div class="small muted" style="margin:-2px 0 8px">경고가 있어도 저장할 수 있습니다. <span data-term="distWarn"></span></div>');

    var b = s.boundaries;
    var $bt = $('<div class="small muted" style="margin:4px 0 10px"></div>').html(
      '적용된 UTC 경계: 학습 <span class="mono">[' + App.toUtc(b.trainFrom) + ', ' + App.toUtc(b.trainTo) + ')</span> · 검증 <span class="mono">[' + App.toUtc(b.valFrom) + ', ' + App.toUtc(b.valTo) + ')</span> · 최종 테스트 <span class="mono">[' + App.toUtc(b.testFrom) + ', ' + App.toUtc(b.testTo) + ')</span>' +
      (b.embargoMinutes ? ' · 경계 여유 ' + b.embargoMinutes + '분' : ''));
    $box.append($bt);

    s.markets.forEach(function (m) {
      var $t = $('<table class="db-table"><thead><tr><th>구간</th><th class="num">전체 행</th><th class="num"><span data-term="validRow">유효 행</span></th>' +
        '<th class="num"><span data-term="nullInputs">입력 결측 포함</span></th><th>정답 분포 (유효 행)</th><th>제외 사유</th></tr></thead><tbody></tbody></table>');
      ['TRAIN', 'VAL', 'TEST', 'NONE'].forEach(function (k) {
        var v = m.segments[k];
        if (!v || (k === 'NONE' && !v.rows)) return;
        var $tr = $('<tr></tr>');
        var $name = $('<td></td>');
        if (SEG_TERM[k]) $name.append($('<span></span>').attr('data-term', SEG_TERM[k]).text(SEG[k])); else $name.text(SEG[k]);
        $tr.append($name);
        $tr.append($('<td class="num"></td>').text(App.formatNumber(v.rows)));
        $tr.append($('<td class="num"></td>').text(k === 'NONE' ? '–' : App.formatNumber(v.valid)));
        $tr.append($('<td class="num"></td>').text(k === 'NONE' ? '–' : App.formatNumber(v.validWithNullInputs)));
        var $dist = $('<td></td>');
        var labels = k === 'TEST' ? (testLabels && testLabels[m.marketId]) : v.labels;
        if (k === 'NONE') $dist.text('–');
        else if (!labels) $dist.append('<span class="small muted">🔒 숨김 — 설정을 고르는 동안 보지 않습니다</span>');
        else {
          $dist.append(LabelView.bar(labels, LABELS, v.valid || 1));
          $dist.append($('<div class="small muted"></div>').text(LABELS.map(function (x) {
            return LabelView.NAME[x] + ' ' + App.ratio(labels[x] || 0, v.valid);
          }).join(' · ')));
        }
        $tr.append($dist);
        var reasons = Object.keys(v.reasons || {}).filter(function (r) { return r !== 'OK' && v.reasons[r] > 0; });
        $tr.append($('<td class="small"></td>').html(reasons.map(function (r) {
          return '<span data-hover-term="reason_' + r + '">' + App.esc(REASON[r] || r) + '</span> ' + App.formatNumber(v.reasons[r]);
        }).join('<br>') || '<span class="faint">없음</span>'));
        $t.find('tbody').append($tr);
      });
      $box.append($('<h3 class="section-title" style="font-size:15px; margin-top:14px"></h3>').text(m.label))
        .append($('<div class="db-table-wrap"></div>').append($t));
    });

    if (s.timeline && s.timeline.length) {
      var $l = $('<ul class="tl-list"></ul>');
      s.timeline.forEach(function (it) {
        $l.append($('<li></li>').addClass('k-' + it.kind)
          .append($('<span class="when"></span>').text(App.toUtc(it.from) + ' → ' + App.toUtc(it.to)))
          .append($('<span class="what"></span>').text(it.text)));
      });
      $box.append('<h3 class="section-title" style="font-size:15px; margin-top:18px">시간순 목록 <span class="count">UTC · 구간 · 빈 기간 · 원본 빈 1분(긴 순 상위)</span></h3>').append($l);
    }
  }

  return { timeline: timeline, render: render };
})(jQuery);
