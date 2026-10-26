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
    var th = $('input[name="threshold"]:checked').val();
    $('#setName').val(assets + ' ' + ivLabel(iv) + ' ±' + th + '% · 학습 ' + $('#trainFrom').val() + '~');
  }

  $('#datasetSel').on('change', function () { selectDataset(false); });
  $(document).on('change', 'input[name="interval"]', function () { renderExtras(); suggestName(); });
  $(document).on('change', 'input[name="asset"], input[name="threshold"]', suggestName);
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
      thresholdPct: Number($('input[name="threshold"]:checked').val()),
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
    $('input[name="threshold"]').each(function () { this.checked = Number(this.value) === Number(COPY.thresholdPct); });
    ['trainFrom', 'trainTo', 'valFrom', 'valTo', 'testFrom', 'testTo'].forEach(function (k) { $('#' + k).val(COPY[k]); });
    refresh();
  } else {
    selectDataset(false);
  }
});
