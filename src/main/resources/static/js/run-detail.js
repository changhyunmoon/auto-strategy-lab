/* 실험 상세: 진행률 · 검증 결과 · 최종 테스트 · 변수 중요도 · 학습 정보 */
$(function () {
  var $page = $('#runPage');
  var id = $page.data('id');
  var valResult = null;

  // ---------- 학습 진행률 (대기 · 학습 중) ----------
  var status = $page.data('status');
  if (status === 'PENDING' || status === 'RUNNING') {
    $('#trainProgress').show();
    (function poll() {
      App.api('GET', '/api/jobs/' + $page.data('job')).done(function (job) {
        $('#trainBar').css('width', job.progress + '%');
        $('#trainText').text(App.jobText(job));
        if (job.status === 'RUNNING') setTimeout(poll, 1500); else location.reload();
      });
    })();
    return;
  }

  $('#deleteBtn').on('click', function () {
    if (!window.confirm('「' + $page.data('name') + '」 실험을 지울까요? 저장된 모델 · 예측 · 결과 파일도 지워집니다.')) return;
    App.api('DELETE', '/api/runs/' + id).done(function () { location.href = '/runs'; });
  });

  if (status !== 'READY') return;

  // ---------- 검증 결과 ----------
  App.api('GET', '/api/runs/' + id + '/result?split=val').done(function (res) {
    if (res.empty) { $('#valResult').html('<div class="empty">결과 파일이 없습니다.</div>'); return; }
    valResult = res;
    renderValExtra(res);
    ResultView.render($('#valResult'), res, { split: 'val' });
    renderImportance(res.importance);
    renderInfo(res);
  });

  /** 저장된 기본 규칙 설명. 예전 실험은 롱 · 숏 기준이었다 */
  function ruleText(s) {
    if (s.allowShort) return "예측 클래스대로 롱 · 숏 (예전 기준)";
    return s.rule === "EV" ? "롱만 · 기대 수익 > 왕복 비용일 때만 매수" : "롱만 · 예측 클래스대로";
  }

  // ---------- 검증: 기본 규칙 거래 성과 + 워크포워드 폴드 ----------
  var foldChart = null;
  function signedPct(v, d) { return v == null ? '–' : (v >= 0 ? '+' : '') + Number(v).toFixed(d == null ? 2 : d) + '%'; }

  function tradeLine(bt, label) {
    if (!bt || !bt.total) return null;
    var t = bt.total;
    var $c = $('<div class="callout" style="margin-bottom:10px"><div class="callout-icon">💰</div><div class="callout-body small"></div></div>');
    $c.find('.callout-body').append($('<b></b>').append($('<span data-term="backtest"></span>').text(label)))
      .append(document.createTextNode(' 순수익 ' + signedPct(t.netReturnPct) + ' (비용 전 ' + signedPct(t.grossReturnPct) + ', 단순 보유 ' + signedPct(t.buyHoldPct) +
        ') · 최대 낙폭 ' + Number(t.maxDrawdownPct).toFixed(2) + '% · 거래 ' + App.formatNumber(t.trades) + '번 · 승률 ' + App.pct(t.winRate)))
      .append($('<div class="faint"></div>').text('기본 규칙: ' + ruleText(bt.settings) + ', 예측 기간만큼 보유, 수수료 ' + bt.settings.feePct + '% + 슬리피지 ' +
        bt.settings.slippagePct + '% (한쪽). 다른 규칙 · 비용은 「💰 거래 성과」 탭에서 계산합니다.'));
    return $c;
  }

  function renderValExtra(res) {
    var $box = $('#valExtra').empty();
    var v = res.validation || {};
    if (v.label) {
      $box.append($('<div class="small muted" style="margin-bottom:8px"></div>').text('검증 방식: ' + v.label +
        (v.folds > 1 ? ' · 폴드 ' + v.folds + ' · purge ' + v.purgeBars + '봉' : '') + ' — ' + v.finalModel));
    }
    var line = tradeLine(res.backtest, '검증 구간 거래 성과 (기본 규칙)');
    if (line) $box.append(line);
    if (!res.folds) return;

    var $s = $('<div class="result-section" style="margin-top:12px"></div>').appendTo($box);
    $s.append($('<h3></h3>').append('<span data-term="walkForward">폴드별 성능</span>')
      .append($('<span class="count"></span>').text('폴드마다 앞쪽으로 학습 → 다음 블록 검증')));
    var el = $('<div class="chart chart--sm"></div>').appendTo($s)[0];
    foldChart = echarts.init(el, 'clean');
    var f = res.folds;
    foldChart.setOption({
      grid: { left: 50, right: 60, top: 36, bottom: 30 }, legend: { top: 0 },
      tooltip: { trigger: 'axis', formatter: function (ps) {
        var x = f[ps[0].dataIndex];
        return '폴드 ' + x.fold + ' · 검증 ' + App.toKst(x.valFrom) + ' ~ ' + App.toKst(x.valTo) + '<br>' +
          ps.map(function (p) { return p.marker + p.seriesName + ' ' + (p.seriesName.indexOf('%') >= 0 ? Number(p.value).toFixed(2) : Number(p.value).toFixed(3)); }).join('<br>');
      } },
      xAxis: { type: 'category', data: f.map(function (x) { return '폴드 ' + x.fold; }) },
      yAxis: [{ type: 'value', name: '점수', min: 0, max: 1 }, { type: 'value', name: '순수익 %', splitLine: { show: false } }],
      series: [
        { name: 'Macro F1', type: 'line', data: f.map(function (x) { return x.macroF1; }), symbolSize: 7 },
        { name: '균형 정확도', type: 'line', data: f.map(function (x) { return x.balancedAccuracy; }), symbolSize: 5 },
        { name: '최빈 클래스 F1', type: 'line', data: f.map(function (x) { return x.baselineMacroF1; }), lineStyle: { type: 'dashed' }, symbolSize: 4, itemStyle: { color: '#787774' } },
        { name: '순수익 %', type: 'bar', yAxisIndex: 1, barMaxWidth: 26, data: f.map(function (x) { return { value: x.netReturnPct, itemStyle: { color: x.netReturnPct >= 0 ? 'rgba(224,62,62,.35)' : 'rgba(35,131,226,.35)' } }; }) }
      ]
    });
    var $t = $('<table class="db-table db-table--compact"><thead><tr><th><span data-term="fold">폴드</span></th><th>학습 (KST)</th><th>검증 (KST)</th><th class="num">학습 행</th><th class="num">검증 행</th>' +
      '<th class="num"><span data-term="macroF1">Macro F1</span></th><th class="num"><span data-term="baseline">최빈 F1</span></th><th class="num"><span data-term="balancedAccuracy">균형 정확도</span></th>' +
      '<th class="num"><span data-term="netReturn">순수익</span></th><th class="num"><span data-term="mdd">최대 낙폭</span></th><th class="num">거래</th></tr></thead><tbody></tbody></table>');
    f.forEach(function (x) {
      var better = x.macroF1 != null && x.baselineMacroF1 != null && x.macroF1 > x.baselineMacroF1;
      $t.find('tbody').append($('<tr></tr>').append($('<td></td>').text(x.fold))
        .append($('<td class="num small"></td>').text(App.toKst(x.trainFrom).slice(0, 10) + ' ~ ' + App.toKst(x.trainTo).slice(0, 16)))
        .append($('<td class="num small"></td>').text(App.toKst(x.valFrom).slice(0, 16) + ' ~ ' + App.toKst(x.valTo).slice(0, 16)))
        .append($('<td class="num"></td>').text(App.formatNumber(x.trainRows))).append($('<td class="num"></td>').text(App.formatNumber(x.valRows)))
        .append($('<td class="num"></td>').text(App.fixed(x.macroF1)).addClass(better ? 'diff-up' : 'diff-down'))
        .append($('<td class="num muted"></td>').text(App.fixed(x.baselineMacroF1)))
        .append($('<td class="num"></td>').text(App.pct(x.balancedAccuracy)))
        .append($('<td class="num"></td>').text(signedPct(x.netReturnPct)))
        .append($('<td class="num"></td>').text(Number(x.maxDrawdownPct).toFixed(2) + '%'))
        .append($('<td class="num"></td>').text(App.formatNumber(x.trades))));
    });
    $('<div class="db-table-wrap" style="margin-top:8px"></div>').append($t).appendTo($s);
    var f1s = f.map(function (x) { return x.macroF1 || 0; });
    var mean = f1s.reduce(function (a, b) { return a + b; }, 0) / f1s.length;
    var sd = Math.sqrt(f1s.reduce(function (a, b) { return a + (b - mean) * (b - mean); }, 0) / f1s.length);
    var wins = f.filter(function (x) { return x.macroF1 != null && x.baselineMacroF1 != null && x.macroF1 > x.baselineMacroF1; }).length;
    var profit = f.filter(function (x) { return x.netReturnPct > 0; }).length;
    $s.append($('<div class="small" style="margin-top:6px"></div>').append('<span data-term="foldStability">안정성</span>')
      .append(document.createTextNode(': Macro F1 평균 ' + mean.toFixed(3) + ' ± ' + sd.toFixed(3) + ' · 최저 ' + Math.min.apply(null, f1s).toFixed(3) +
        ' · 최빈 클래스보다 나은 폴드 ' + wins + '/' + f.length + ' · 순수익 플러스 폴드 ' + profit + '/' + f.length)));
    $s.append($('<details class="help"><summary>ⓘ 읽는 법</summary><div class="help-body"></div></details>').find('.help-body')
      .html('<p>폴드마다 그 시점까지의 데이터로만 학습하고 바로 다음 블록을 예측했습니다. 실제 운영과 같은 순서입니다.</p>' +
        '<p>점수가 폴드마다 크게 오르내리거나, 일부 폴드에서 최빈 클래스(점선)보다 낮다면 특정 시기에만 통하는 모델일 수 있습니다.</p>' +
        '<p>아래 「검증 결과」의 차트 · 표는 모든 폴드의 검증 블록 예측(폴드 밖 예측)을 이어 붙여 그린 것입니다.</p>').end());
  }

  // ---------- 최종 테스트 ----------
  var testCount = Number($page.data('test-count')) || 0;
  function testIntro() {
    $('#testCountText').text(testCount ? '지금까지 ' + testCount + '회 실행 · 다시 실행해도 같은 모델이라 결과는 같습니다 (기록만 늘어남).' : '아직 실행하지 않았습니다.');
    $('#testBtn').text(testCount ? '다시 실행 (기록됨)' : '최종 테스트 실행');
  }
  testIntro();

  function loadTest() {
    App.api('GET', '/api/runs/' + id + '/result?split=test').done(function (res) {
      if (res.empty) return;
      $('#testCsv').show();
      ResultView.render($('#testResult'), res, { split: 'test' });
      var line = tradeLine(res.backtest, '최종 테스트 거래 성과 (기본 규칙)');
      if (line) $('#testResult').prepend(line);
      if (valResult) appendCompare(res);
    });
  }

  /** 검증 → 최종 테스트 점수 변화 */
  function appendCompare(test) {
    var v = valResult.byAsset.ALL.summary, t = test.byAsset.ALL.summary;
    var $box = $('<div class="callout" style="margin-bottom:12px"><div class="callout-icon">↔️</div><div class="callout-body small"></div></div>');
    var parts = [['Macro F1', v.macroF1, t.macroF1, 3], ['균형 정확도', v.balancedAccuracy, t.balancedAccuracy, 'pct'], ['정확도', v.accuracy, t.accuracy, 'pct']];
    var $b = $box.find('.callout-body').append('<b>검증 → 최종 테스트</b> ');
    parts.forEach(function (p) {
      var fmt = p[3] === 'pct' ? App.pct : function (x) { return App.fixed(x, 3); };
      $b.append(document.createTextNode(' · ' + p[0] + ' ' + fmt(p[1]) + ' → ' + fmt(p[2])));
    });
    $b.append($('<div class="faint" style="margin-top:4px"></div>').text('최종 테스트 점수가 검증보다 크게 낮으면, 검증 구간에 맞춰 고른 운이 섞였을 수 있습니다.'));
    $('#testResult').prepend($box);
  }

  function pollTest(jobId) {
    $('#testProgress').show();
    if ($('#testCancelBtn').attr('data-cancel-job') !== String(jobId)) {
      $('#testCancelBtn').attr('data-cancel-job', jobId).prop('disabled', false).text('■ 테스트 중지');
    }
    App.api('GET', '/api/jobs/' + jobId).done(function (job) {
      $('#testBar').css('width', job.progress + '%');
      $('#testText').text(App.jobText(job));
      if (job.status === 'RUNNING') { setTimeout(function () { pollTest(jobId); }, 1500); return; }
      $('#testProgress').hide();
      $('#testBtn').prop('disabled', false);
      if (job.status === 'SUCCEEDED') { testCount++; testIntro(); loadTest(); $(document).trigger('test:done'); App.toast('최종 테스트를 마쳤습니다.'); }
      else App.toast((job.message && job.message.indexOf('최종 테스트를 중지') === 0 ? '' : '최종 테스트 실패: ') + (job.message || ''), 'red');
    });
  }

  $('#testBtn').on('click', function () {
    var msg = testCount
      ? '이미 ' + testCount + '회 실행했습니다. 다시 실행하면 횟수가 기록됩니다. 계속할까요?'
      : '최종 테스트를 실행할까요? 이 결과를 보고 설정을 바꾸면 이 테스트 구간은 더 이상 「처음 보는 데이터」가 아닙니다.';
    if (!window.confirm(msg)) return;
    $('#testBtn').prop('disabled', true);
    App.api('POST', '/api/runs/' + id + '/test').done(function (res) { pollTest(res.jobId); })
      .fail(function () { $('#testBtn').prop('disabled', false); });
  });

  var testStatus = $page.data('test-status');
  if (testStatus === 'DONE') loadTest();
  if (testStatus === 'RUNNING' && $page.data('test-job')) { $('#testBtn').prop('disabled', true); pollTest($page.data('test-job')); }

  $(document).on('tab:shown', function (e, name) {
    if (name === 'val') { ResultView.resizeAll($('#valResult')); if (foldChart) foldChart.resize(); }
    if (name === 'test') ResultView.resizeAll($('#testResult'));
    if (name === 'importance') impCharts.forEach(function (c) { c.resize(); });
  });

  // ---------- 변수 중요도 ----------
  var impCharts = [];
  function renderImportance(imp) {
    var $box = $('#importanceBox').empty();
    if (!imp) { $box.html('<div class="empty">중요도 정보가 없습니다.</div>'); return; }
    function bars(title, sub, list, withStd, helpLines) {
      var $s = $('<div class="result-section"></div>').appendTo($box);
      $s.append($('<h3></h3>').append($('<span></span>').attr('data-term', title === '순열 중요도' ? 'permutation' : 'nativeImportance').text(title))
        .append($('<span class="count"></span>').text(sub)));
      if (!list) { $s.append($('<div class="small faint"></div>').text('계산하지 않았습니다.')); return; }
      var top = list.slice(0, 30).reverse();
      var el = $('<div class="chart"></div>').css('height', Math.max(240, 40 + top.length * 22) + 'px').appendTo($s)[0];
      var c = echarts.init(el, 'clean');
      c.setOption({
        grid: { left: 210, right: 70, top: 10, bottom: 30 },
        tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, formatter: function (ps) {
          var r = top[ps[0].dataIndex];
          return r.column + '<br>' + Number(r.value).toFixed(5) + (withStd && r.std != null ? ' ± ' + Number(r.std).toFixed(5) : '');
        } },
        xAxis: { type: 'value' },
        yAxis: { type: 'category', data: top.map(function (r) { return r.column; }), axisLabel: { fontFamily: 'monospace', fontSize: 11, width: 200, overflow: 'truncate' } },
        series: [{ type: 'bar', barWidth: 12, data: top.map(function (r) { return { value: r.value, itemStyle: { color: r.value < 0 ? '#e03e3e' : '#2383e2' } }; }),
          label: { show: true, position: 'right', fontSize: 10, formatter: function (p) { return Number(p.value).toFixed(4); } } }]
      });
      impCharts.push(c);
      $s.append($('<details class="help"><summary>ⓘ 읽는 법</summary><div class="help-body"></div></details>')
        .find('.help-body').html(helpLines.map(function (l) { return '<p>' + l + '</p>'; }).join('')).end());
    }
    bars('순열 중요도', imp.permutation ? '검증 표본 ' + App.formatNumber(imp.permutationRows) + '행 · ' + imp.permutationRepeats + '회 반복 · 기준 Macro F1 ' + App.fixed(imp.permutationBase) : '',
      imp.permutation, true, [
        '<b>정의:</b> 학습이 끝난 모델은 그대로 두고, 검증 자료에서 한 열의 값만 무작위로 섞었을 때 Macro F1 이 얼마나 떨어지는지입니다. 클수록 모델이 그 열에 기대고 있습니다.',
        '<b>주의:</b> 서로 비슷한 열(예: 여러 기간의 이동평균 괴리)은 하나를 섞어도 다른 열이 대신해서 낮게 보일 수 있습니다. 0 근처나 음수는 「이 모델에서는 쓸모가 거의 없음」에 가깝습니다. 인과 관계를 뜻하지 않습니다.'
      ]);
    bars('모델 자체 중요도', '학습 구간 기준', imp.native, false, [imp.nativeNote || '', '모델마다 계산 방식이 달라 서로 다른 모델끼리 값을 직접 비교하지 않습니다.']);
  }

  // ---------- 학습 정보 ----------
  function renderInfo(res) {
    var $box = $('#infoBox').empty();
    var tr = res.train || {}, va = res.byAsset.ALL.summary;
    var $s = $('<div class="result-section"></div>').appendTo($box);
    $s.append('<h3><span data-term="overfitGap">학습 vs 검증</span> <span class="count">학습 점수는 최종 모델이 학습에 쓴 표본(최대 10만 행)을 다시 평가한 값</span></h3>');
    var $t = $('<table class="db-table"><thead><tr><th>지표</th><th class="num">학습 (재평가)</th><th class="num">검증</th><th class="num">차이</th></tr></thead><tbody></tbody></table>');
    [['Macro F1', 'macroF1', 3], ['균형 정확도', 'balancedAccuracy', 'pct'], ['정확도', 'accuracy', 'pct'], ['Log loss', 'logLoss', 3]].forEach(function (m) {
      var a = tr[m[1]], b = va[m[1]], fmt = m[2] === 'pct' ? App.pct : function (x) { return App.fixed(x, 3); };
      var d = a != null && b != null ? a - b : null;
      $t.find('tbody').append($('<tr></tr>').append($('<td></td>').text(m[0])).append($('<td class="num"></td>').text(fmt(a)))
        .append($('<td class="num"></td>').text(fmt(b))).append($('<td class="num"></td>').text(d == null ? '–' : (m[2] === 'pct' ? (d * 100).toFixed(1) + '%p' : d.toFixed(3)))));
    });
    $('<div class="db-table-wrap"></div>').append($t).appendTo($s);
    $s.append($('<details class="help"><summary>ⓘ 읽는 법</summary><div class="help-body"><p>학습 점수만 높고 검증 점수가 크게 낮으면 학습 자료를 외운 것(과적합)일 수 있습니다. 트리 깊이 · 리프 최소 표본 · 규제를 조절해 보세요.</p></div></details>'));

    var info = res.info || {};
    var $u = $('<div class="result-section"></div>').appendTo($box);
    $u.append('<h3>학습 단위별 표본</h3>');
    var $ut = $('<table class="db-table db-table--compact"><thead><tr><th>단위</th><th class="num">학습 구간 행</th><th class="num">조기 종료용 제외 후</th><th class="num">불균형 처리 후</th><th class="num">실제 사용</th><th class="num">조기 종료 자료</th><th>학습 구간 (KST)</th><th class="num">하락 · 중립 · 상승 비율</th></tr></thead><tbody></tbody></table>');
    (info.units || []).forEach(function (u) {
      $ut.find('tbody').append($('<tr></tr>').append($('<td></td>').text(u.unit))
        .append($('<td class="num"></td>').text(App.formatNumber(u.trainRows)))
        .append($('<td class="num"></td>').text(App.formatNumber(u.fitPoolRows)))
        .append($('<td class="num"></td>').text(App.formatNumber(u.balancedRows)))
        .append($('<td class="num"></td>').text(App.formatNumber(u.usedRows)))
        .append($('<td class="num"></td>').text(u.stopRows ? App.formatNumber(u.stopRows) : '–'))
        .append($('<td class="num small"></td>').text(App.toKst(u.trainFrom) + ' ~ ' + App.toKst(u.trainTo)))
        .append($('<td class="num"></td>').text((u.prior || []).map(function (p) { return App.pct(p, 1); }).join(' · '))));
    });
    $('<div class="db-table-wrap"></div>').append($ut).appendTo($u);

    var $n = $('<div class="result-section"></div>').appendTo($box);
    $n.append('<h3>기록</h3>');
    var $p = $('<div class="props"></div>').appendTo($n);
    function row(label, value) {
      $p.append($('<div class="prop"></div>').append($('<div class="prop-label"></div>').text(label)).append($('<div class="prop-value small"></div>').text(value)));
    }
    row('조기 종료', info.earlyStopping || '');
    row('참고', info.notes || '없음');
    row('학습 시각', info.trainedAt ? App.toKst(info.trainedAt) : '');
    row('라이브러리', info.library || '');
    row('입력 열', (info.inputs || []).length + '개');
    var $cols = $('<div style="display:flex;flex-wrap:wrap;gap:4px;margin-top:6px"></div>').appendTo($n);
    (info.inputs || []).forEach(function (c) { $cols.append($('<span class="tag mono"></span>').text(c)); });
  }
});
