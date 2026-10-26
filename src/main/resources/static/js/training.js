/* 모델 학습 화면: 학습 데이터 · 모델 · 공통 설정 고르기 → 실험 만들기 */
$(function () {
  if (!$('#trainForm').length) return;

  // 노션 「모델 후보 조사」의 1차 추천: 기준 3종 + 로지스틱 + 랜덤 포레스트 + 그래디언트 부스팅
  var FIRST = ['BASELINE_MAJORITY', 'BASELINE_STRATIFIED', 'BASELINE_PERSISTENCE', 'LOGISTIC', 'RANDOM_FOREST', 'GRADIENT_BOOSTING'];

  // ---------- 학습 데이터 ----------
  function setHint() {
    var $o = $('#setSel option:selected');
    if (!$o.length) return;
    $('#setHint').text($o.data('assets') + ' · ' + $o.data('interval') + ' · 정답 ±' + $o.data('threshold') + '% · ' +
      App.formatNumber($o.data('rows')) + '행 × ' + $o.data('cols') + '열 · 학습 ' + $o.data('train') + ' · 검증 ' + $o.data('val') + ' · 테스트 ' + $o.data('test'));
    suggestName();
  }

  // ---------- 모델 카드 ----------
  var $cards = $('#modelCards');
  var family = null;
  MODELS.forEach(function (m) {
    if (m.family !== family) {
      family = m.family;
      $cards.append($('<div class="small muted" style="margin:12px 0 4px;font-weight:600"></div>').text(family));
    }
    var $c = $('<div class="model-card"></div>').attr('data-code', m.code);
    var $head = $('<label class="model-head"></label>')
      .append($('<input type="checkbox" class="form-check-input model-check">').val(m.code))
      .append($('<div></div>').append($('<div class="model-name"></div>').text(m.label)).append($('<div class="model-desc"></div>').text(m.description)));
    var $tags = $('<span class="model-tags"></span>');
    if (m.probability) $tags.append('<span class="tag">확률</span>');
    if (m.earlyStopping) $tags.append('<span class="tag">조기 종료</span>');
    if (m.classWeight) $tags.append('<span class="tag">클래스 가중치</span>');
    $head.append($tags);
    $c.append($head);
    if (m.params.length) {
      $c.append($('<span class="param-toggle">⚙️ 설정 ' + m.params.length + '개 ▾</span>'));
      var $grid = $('<div class="model-params"></div>');
      m.params.forEach(function (p) {
        var $f = $('<div></div>');
        $f.append($('<label></label>').text(p.label).attr('title', p.help || ''));
        $f.append($('<input type="number" class="form-control form-control-sm param">').attr({
          'data-key': p.key, min: p.min, max: p.max, step: p.type === 'int' ? 1 : 'any', title: p.help || ''
        }).val(p.defaultValue));
        $grid.append($f);
      });
      $c.append($grid);
    }
    $cards.append($c);
  });
  $cards.on('click', '.param-toggle', function () { $(this).closest('.model-card').toggleClass('open'); });
  $cards.on('change', '.model-check', updateCount);

  function pick(codes) {
    $('.model-check').each(function () { this.checked = codes.indexOf(this.value) >= 0; });
    updateCount();
  }
  $('#pickFirst').on('click', function () { pick(FIRST); });
  $('#pickNone').on('click', function () { pick([]); });

  function selected() {
    return $('.model-check:checked').map(function () { return this.value; }).get();
  }

  function updateCount() {
    var n = selected().length;
    $('#modelCount').text(n ? n + '개 선택 → 실험 ' + n + '개' : '');
    $('#submitBtn').prop('disabled', n === 0);
    var models = MODELS.filter(function (m) { return selected().indexOf(m.code) >= 0; });
    var balance = $('input[name="balance"]:checked').val();
    var notes = [];
    if (balance === 'CLASS_WEIGHT' && models.some(function (m) { return !m.classWeight && !m.baseline; })) {
      notes.push('클래스 가중치는 랜덤 포레스트에만 적용되고, 다른 모델은 「미적용」으로 기록됩니다.');
    }
    $('#submitHint').text(notes.join(' '));
    suggestName();
  }

  // ---------- 선택지 설명 ----------
  function describe(name) {
    var v = $('input[name="' + name + '"]:checked').val();
    var c = CHOICES[name].filter(function (x) { return x.code === v; })[0];
    $('#' + { trainUnit: 'trainUnitHint', balance: 'balanceHint', earlyStopping: 'earlyHint' }[name]).text(c ? c.description : '');
  }
  ['trainUnit', 'balance', 'earlyStopping'].forEach(function (n) {
    $(document).on('change', 'input[name="' + n + '"]', function () { describe(n); updateCount(); });
    describe(n);
  });

  var nameTouched = false;
  $('#runName').on('input', function () { nameTouched = true; });
  function suggestName() {
    if (nameTouched) return;
    var set = $('#setSel option:selected').text();
    var n = selected().length;
    var model = n === 1 ? MODELS.filter(function (m) { return m.code === selected()[0]; })[0].label : n + '개 모델';
    $('#runName').val(set + ' · ' + model);
  }

  // ---------- 보내기 ----------
  $('#submitBtn').on('click', function () {
    var models = selected().map(function (code) {
      var params = {};
      $('.model-card[data-code="' + code + '"] .param').each(function () { params[$(this).data('key')] = $(this).val(); });
      return { type: code, params: params };
    });
    var many = models.length > 1;
    var body = {
      trainingSetId: Number($('#setSel').val()),
      name: many ? $.trim($('#runName').val()).replace(/ · \d+개 모델$/, '') : $.trim($('#runName').val()),
      models: models,
      trainUnit: $('input[name="trainUnit"]:checked').val(),
      balance: $('input[name="balance"]:checked').val(),
      earlyStopping: $('input[name="earlyStopping"]:checked').val(),
      seed: Number($('#seed').val()),
      maxTrainRows: Number($('#maxTrainRows').val()),
      permutationImportance: $('#permutation').is(':checked'),
      memo: $('#memo').val()
    };
    $('#submitBtn').prop('disabled', true);
    App.api('POST', '/api/runs', body).done(function (res) {
      location.href = res.runIds.length === 1 ? '/runs/' + res.runIds[0] : '/runs?trainingSetId=' + res.trainingSetId;
    }).fail(function () { $('#submitBtn').prop('disabled', false); });
  });

  $('#setSel').on('change', setHint);

  // ---------- 같은 설정으로 다시 (?copy=실험ID) ----------
  if (COPY) {
    pick([COPY.model]);
    var $card = $('.model-card[data-code="' + COPY.model + '"]').addClass('open');
    Object.keys(COPY.params || {}).forEach(function (k) { $card.find('.param[data-key="' + k + '"]').val(COPY.params[k]); });
    ['trainUnit', 'balance', 'earlyStopping'].forEach(function (n) {
      $('input[name="' + n + '"][value="' + COPY[n] + '"]').prop('checked', true);
      describe(n);
    });
    $('#seed').val(COPY.seed);
    $('#maxTrainRows').val(COPY.maxTrainRows);
    $('#permutation').prop('checked', COPY.permutationImportance);
  } else {
    pick(FIRST);
  }
  setHint();
  updateCount();
});
