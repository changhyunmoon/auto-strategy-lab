/* 학습 데이터 만들기 화면 */
$(function () {
  if (!$('#setForm').length) return;
  var DAY = 86400000;
  var ds = null;

  function parse(s) { return s ? Date.parse(s + 'T00:00:00Z') : NaN; }
  function fmt(ms) { return new Date(ms).toISOString().slice(0, 10); }
  function days(a, b) { return Math.round((parse(b) - parse(a)) / DAY) + 1; }
  function ivLabel(iv) { return iv.replace('m', '분'); }
  function ivMin(iv) { return parseInt(iv, 10); }

  function chip(type, name, value, text, checked) {
    var $l = $('<label class="chip"></label>');
    $l.append($('<input>').attr({ type: type, name: name, value: value }).prop('checked', !!checked));
    return $l.append(document.createTextNode(text));
  }

  function selectDataset(keep) {
    ds = DATASETS.filter(function (d) { return String(d.id) === $('#datasetSel').val(); })[0];
    if (!ds) return;
    $('#datasetHint').text(ds.from + ' ~ ' + ds.to + ' · ' + ds.intervals.map(ivLabel).join('·') + ' · 지표 ' + ds.featureColumns + '열');
    $('#assetChips').empty();
    ds.assets.forEach(function (a) { $('#assetChips').append(chip('checkbox', 'asset', a.id, a.code, true)); });
    $('#intervalChips').empty();
    ds.intervals.forEach(function (iv, i) { $('#intervalChips').append(chip('radio', 'interval', iv, ivLabel(iv), i === 0)); });
    $('#groupChips').empty();
    GROUPS.forEach(function (g) {
      var has = ds.groups.indexOf(g.code) >= 0;
      var $c = chip('checkbox', 'group', g.code, g.label, has);
      if (!has) $c.addClass('is-disabled').attr('title', '이 데이터세트에 없는 그룹');
      $('#groupChips').append($c);
    });
    renderExtras();
    if (!keep) preset('r60');
  }

  function renderExtras() {
    var decision = $('input[name="interval"]:checked').val();
    var checked = $('input[name="extra"]:checked').map(function () { return this.value; }).get();
    $('#extraChips').empty();
    var any = false;
    ds.intervals.forEach(function (iv) {
      if (ivMin(iv) <= ivMin(decision)) return;
      any = true;
      $('#extraChips').append(chip('checkbox', 'extra', iv, ivLabel(iv) + ' 지표', checked.indexOf(iv) >= 0));
    });
    if (!any) $('#extraChips').append('<span class="small muted">붙일 수 있는 더 긴 간격이 없습니다.</span>');
  }

  /** 빠른 기간 선택 */
  function preset(v) {
    var start = parse(ds.from), end = parse(ds.to), total = Math.round((end - start) / DAY) + 1;
    var trainEnd, valEnd;
    if (v.charAt(0) === 'r') {
      var r = { r60: [0.6, 0.2], r70: [0.7, 0.15], r50: [0.5, 0.25] }[v];
      trainEnd = start + (Math.max(1, Math.round(total * r[0])) - 1) * DAY;
      valEnd = trainEnd + Math.max(1, Math.round(total * r[1])) * DAY;
    } else {
      var months = parseInt(v.slice(1), 10);
      var testStart = new Date(end + DAY);
      testStart.setUTCMonth(testStart.getUTCMonth() - months);
      var valStart = new Date(testStart.getTime());
      valStart.setUTCMonth(valStart.getUTCMonth() - months);
      valEnd = Math.max(start + DAY, testStart.getTime() - DAY);
      trainEnd = Math.max(start, valStart.getTime() - DAY);
      if (trainEnd >= valEnd) trainEnd = valEnd - DAY;
    }
    valEnd = Math.min(valEnd, end - DAY);
    $('#trainFrom').val(fmt(start));
    $('#trainTo').val(fmt(trainEnd));
    $('#valFrom').val(fmt(trainEnd + DAY));
    $('#valTo').val(fmt(valEnd));
    $('#testFrom').val(fmt(valEnd + DAY));
    $('#testTo').val(fmt(end));
    $('input[name="preset"][value="' + v + '"]').prop('checked', true);
    refresh();
  }

  function refresh() {
    var p = { train: [$('#trainFrom').val(), $('#trainTo').val()], val: [$('#valFrom').val(), $('#valTo').val()], test: [$('#testFrom').val(), $('#testTo').val()] };
    ['train', 'val', 'test'].forEach(function (k) {
      $('#' + k + 'Days').text(p[k][0] && p[k][1] ? days(p[k][0], p[k][1]) + '일' : '');
    });
    // 타임라인: 데이터세트 전체 기간 위에 세 구간을 그린다
    var start = parse(ds.from), end = parse(ds.to) + DAY, span = end - start;
    var $tl = $('#timeline').empty(), cursor = start;
    var labels = { train: '학습', val: '검증', test: '테스트' };
    ['train', 'val', 'test'].forEach(function (k) {
      var a = parse(p[k][0]), b = parse(p[k][1]) + DAY;
      if (isNaN(a) || isNaN(b)) return;
      if (a > cursor) $tl.append($('<div class="seg seg-gap"></div>').css('flex', String((a - cursor) / span)));
      $tl.append($('<div class="seg"></div>').addClass('seg-' + k).css('flex', String(Math.max(0, b - a) / span)).text(labels[k]));
      cursor = Math.max(cursor, b);
    });
    if (cursor < end) $tl.append($('<div class="seg seg-gap"></div>').css('flex', String((end - cursor) / span)));
    $('#tlStart').text(ds.from);
    $('#tlEnd').text(ds.to);

    var warn = [];
    if (p.train[1] >= p.val[0]) warn.push('검증 시작일은 학습 종료일 다음 날 이후여야 합니다.');
    if (p.val[1] >= p.test[0]) warn.push('테스트 시작일은 검증 종료일 다음 날 이후여야 합니다.');
    ['train', 'val', 'test'].forEach(function (k) {
      if (p[k][0] > p[k][1]) warn.push(labels[k] + ' 시작일이 종료일보다 늦습니다.');
      if (p[k][0] < ds.from || p[k][1] > ds.to) warn.push(labels[k] + ' 기간이 데이터세트 범위를 벗어납니다.');
    });
    $('#periodWarnText').text(warn.join(' '));
    $('#periodWarn').toggle(warn.length > 0);
    suggestName();
  }

  var nameTouched = false;
  function suggestName() {
    if (nameTouched) return;
    var assets = $('input[name="asset"]:checked').map(function () { return $(this).parent().text(); }).get().join('·');
    var iv = $('input[name="interval"]:checked').val() || '';
    if (!$('input[name="labelMethod"]:checked').length) return;
    var s = labelSpec();
    var method = s.labelMethod === 'TRIPLE_BARRIER' ? '목표·손절 ' : '';
    $('#setName').val(assets + ' ' + ivLabel(iv) + ' · ' + method + s.horizon + '봉 ' + thresholdText(s) + ' · 학습 ' + $('#trainFrom').val() + '~');
  }

  // ---------- 정답 정의 ----------
  function radio(name) { return $('input[name="' + name + '"]:checked').val(); }
  function fmtNum(v) { return String(Number(Number(v).toFixed(3))); }

  function labelSpec() {
    return {
      labelMethod: radio('labelMethod'),
      horizon: Number(radio('horizon')),
      thresholdMode: radio('thresholdMode'),
      thresholdPct: Number(radio('thresholdPct')),
      volK: Number(radio('volK')),
      volWindow: Number(radio('volWindow')),
      feePct: Number($('#feePct').val()),
      slippagePct: Number($('#slippagePct').val()),
      marginPct: Number(radio('marginPct'))
    };
  }

  function thresholdText(s) {
    if (s.thresholdMode === 'FIXED') return '±' + fmtNum(s.thresholdPct) + '%';
    if (s.thresholdMode === 'VOLATILITY') return '±(최근 ' + s.volWindow + '봉 변동성 × ' + fmtNum(s.volK) + ' × √' + s.horizon + ')';
    return '±' + fmtNum((s.feePct + s.slippagePct) * 2 + s.marginPct) + '%';
  }

  function updateLabel() {
    var s = labelSpec();
    var iv = $('input[name="interval"]:checked').val() || '1m';
    var minutes = ivMin(iv) * s.horizon;
    var span = minutes >= 60 ? (minutes % 60 ? (minutes / 60).toFixed(1) : minutes / 60) + '시간' : minutes + '분';
    $('.horizon-time').each(function () {
      var m = ivMin(iv) * Number($(this).data('bars'));
      $(this).text(m >= 60 ? (m % 60 ? (m / 60).toFixed(1) : m / 60) + '시간' : m + '분');
    });
    $('.mode-panel').each(function () { $(this).toggle($(this).data('mode') === s.thresholdMode); });
    var thr = thresholdText(s);
    var text = s.labelMethod === 'TRIPLE_BARRIER'
      ? '판단 후 ' + s.horizon + '봉(' + span + ') 안에 현재 종가 대비 +' + thr.replace('±', '') + '(목표가)에 먼저 닿으면 상승, −' + thr.replace('±', '') + '(손절가)에 먼저 닿으면 하락, 둘 다 아니면 중립.'
      : '판단 봉 종가 → ' + s.horizon + '봉(' + span + ') 뒤 종가 수익률이 +기준 이상이면 상승, −기준 이하면 하락, 그 사이는 중립. 기준 ' + thr + '.';
    $('#labelPreview').text(text + (s.thresholdMode === 'COST' ? ' (왕복 비용을 넘는 움직임만 상승 · 하락)' : ''));
    suggestName();
  }
  $(document).on('change', 'input[name="labelMethod"], input[name="horizon"], input[name="thresholdMode"], input[name="thresholdPct"], input[name="volK"], input[name="volWindow"], input[name="marginPct"]', updateLabel);
  $('#feePct, #slippagePct').on('input', updateLabel);

  $('#datasetSel').on('change', function () { selectDataset(false); });
  $(document).on('change', 'input[name="interval"]', function () { renderExtras(); updateLabel(); });
  $(document).on('change', 'input[name="asset"]', suggestName);
  $(document).on('change', 'input[name="preset"]', function () { preset(this.value); });
  $('.period').on('change', function () { $('input[name="preset"]').prop('checked', false); refresh(); });
  $('#setName').on('input', function () { nameTouched = true; });

  $('#submitBtn').on('click', function () {
    var body = {
      name: $.trim($('#setName').val()),
      datasetId: Number($('#datasetSel').val()),
      decisionInterval: $('input[name="interval"]:checked').val(),
      extraIntervals: $('input[name="extra"]:checked').map(function () { return this.value; }).get(),
      assetIds: $('input[name="asset"]:checked').map(function () { return Number(this.value); }).get(),
      featureGroups: $('input[name="group"]:checked').map(function () { return this.value; }).get(),
      includeBarColumns: $('#includeBar').is(':checked'),
      labelMethod: labelSpec().labelMethod, horizon: labelSpec().horizon, thresholdMode: labelSpec().thresholdMode,
      thresholdPct: labelSpec().thresholdPct, volK: labelSpec().volK, volWindow: labelSpec().volWindow,
      feePct: labelSpec().feePct, slippagePct: labelSpec().slippagePct, marginPct: labelSpec().marginPct,
      trainFrom: $('#trainFrom').val(), trainTo: $('#trainTo').val(),
      valFrom: $('#valFrom').val(), valTo: $('#valTo').val(),
      testFrom: $('#testFrom').val(), testTo: $('#testTo').val()
    };
    $('#submitBtn').prop('disabled', true);
    App.api('POST', '/api/trainsets', body).done(function (res) {
      $('#progressBox').show();
      poll(res.jobId, res.trainingSetId);
    }).fail(function () { $('#submitBtn').prop('disabled', false); });
  });

  function poll(jobId, id) {
    App.api('GET', '/api/jobs/' + jobId).done(function (job) {
      $('#progressBar').css('width', job.progress + '%');
      $('#progressText').text(job.progress + '% · ' + (job.step || ''));
      if (job.status === 'RUNNING') {
        setTimeout(function () { poll(jobId, id); }, 1500);
      } else if (job.status === 'SUCCEEDED') {
        location.href = '/trainsets/' + id;
      } else {
        $('#failText').text('실패: ' + (job.message || '알 수 없는 오류'));
        $('#failBox').show();
        $('#submitBtn').prop('disabled', false);
      }
    });
  }

  // ---------- 같은 설정으로 새로 만들기 ----------
  if (COPY && DATASETS.some(function (d) { return d.id === COPY.datasetId; })) {
    $('#datasetSel').val(String(COPY.datasetId));
    selectDataset(true);
    $('input[name="asset"]').each(function () { this.checked = COPY.assetIds.indexOf(Number(this.value)) >= 0; });
    $('input[name="interval"][value="' + COPY.decisionInterval + '"]').prop('checked', true);
    renderExtras();
    $('input[name="extra"]').each(function () { this.checked = COPY.extraIntervals.indexOf(this.value) >= 0; });
    $('input[name="group"]').each(function () { this.checked = COPY.featureGroups.indexOf(this.value) >= 0; });
    $('#includeBar').prop('checked', COPY.includeBarColumns);
    var L = COPY.label || {};
    ['labelMethod', 'horizon', 'thresholdMode', 'thresholdPct', 'volK', 'volWindow', 'marginPct'].forEach(function (k) {
      if (L[k] == null) return;
      $('input[name="' + k + '"]').each(function () { this.checked = String(this.value) === String(L[k]) || Number(this.value) === Number(L[k]); });
    });
    if (L.feePct != null) $('#feePct').val(L.feePct);
    if (L.slippagePct != null) $('#slippagePct').val(L.slippagePct);
    updateLabel();
    ['trainFrom', 'trainTo', 'valFrom', 'valTo', 'testFrom', 'testTo'].forEach(function (k) { $('#' + k).val(COPY[k]); });
    refresh();
  } else {
    selectDataset(false);
  }
  updateLabel();
});
