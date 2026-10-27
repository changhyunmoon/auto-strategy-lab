/* 모든 페이지 공통: API 호출 · 오류 표시 · 숫자/시간 표시 */
var App = (function ($) {

  /** JSON API 호출. 실패하면 화면 상단에 오류를 띄운다. */
  function api(method, url, data) {
    return $.ajax({
      method: method,
      url: url,
      contentType: 'application/json',
      data: data && method !== 'GET' ? JSON.stringify(data) : data,
      dataType: 'json'
    }).fail(function (xhr) {
      var msg = (xhr.responseJSON && xhr.responseJSON.message) || ('요청 실패 (' + xhr.status + ')');
      toast(msg, 'red');
    });
  }

  /** 화면 아래쪽에 잠깐 나타나는 알림. */
  function toast(message, color) {
    var $t = $('<div class="callout callout-' + (color || 'blue') + '"></div>')
      .text(message)
      .css({ position: 'fixed', right: 20, bottom: 20, zIndex: 50, maxWidth: 360, boxShadow: '0 6px 18px rgba(15,15,15,.1)' })
      .appendTo('body');
    setTimeout(function () { $t.fadeOut(200, function () { $t.remove(); }); }, 3000);
  }

  function formatNumber(n) {
    return n == null ? '' : Number(n).toLocaleString('ko-KR');
  }

  /** UTC ISO 문자열 → KST 'YYYY-MM-DD HH:mm' */
  function toKst(iso) {
    if (!iso) return '';
    var d = new Date(new Date(iso).getTime() + 9 * 3600 * 1000);
    return d.toISOString().slice(0, 16).replace('T', ' ');
  }

  /** 탭: .tabs 안의 .tab[data-tab] 를 누르면 같은 이름의 .tab-panel[data-panel] 을 보여준다. */
  $(document).on('click', '.tabs .tab', function () {
    var $tab = $(this), name = $tab.data('tab');
    var $scope = $tab.closest('[data-tabs-scope]');
    if (!$scope.length) $scope = $(document.body);
    $tab.addClass('active').siblings().removeClass('active');
    $scope.find('.tab-panel').removeClass('active').filter('[data-panel="' + name + '"]').addClass('active');
    $(document).trigger('tab:shown', [name]);
  });

  /** 「중지」 버튼: data-cancel-job="작업 번호" 를 붙이면 확인 후 그 작업을 멈춘다. */
  $(document).on('click', '[data-cancel-job]', function (e) {
    e.preventDefault();
    e.stopPropagation(); // 목록 행 클릭(상세로 이동)과 겹치지 않게
    var $b = $(this), jobId = $b.attr('data-cancel-job');
    if (!jobId || !window.confirm('진행 중인 작업을 중지할까요?\n지금까지 받은 원본 1분봉은 그대로 남고, 만들던 결과는 「실패(중지)」로 표시됩니다.\n대기 중인 작업이면 시작하지 않고 끝납니다.')) return;
    $b.prop('disabled', true).text('중지하는 중…');
    api('POST', '/api/jobs/' + jobId + '/cancel').done(function () {
      toast('중지를 요청했습니다. 몇 초 안에 멈춥니다.');
      $(document).trigger('job:cancel-requested', [jobId]);
    }).fail(function () { $b.prop('disabled', false).text('■ 중지'); });
  });

  /** 「■ 중지」 버튼 (위 클릭 처리와 함께 동작) */
  function cancelButton(jobId, label) {
    return $('<button type="button" class="btn btn-danger-ghost btn-sm"></button>').attr('data-cancel-job', jobId).text(label || '■ 중지');
  }

  /**
   * 작업 진행 한 줄: "62% · 단계 · 메시지".
   * 앞 작업을 기다리는 중이면 대기 순서를, 중지를 눌렀으면 멈추는 중임을 알려준다.
   */
  function jobText(job) {
    if (job.status === 'RUNNING' && job.cancelRequested) {
      return job.waiting ? '중지 요청됨 — 차례가 오면 시작하지 않고 바로 끝납니다.' : '중지하는 중… 다음 확인 지점에서 멈춥니다.';
    }
    if (job.status === 'RUNNING' && job.waiting) {
      return '대기 중 — 앞 작업 ' + job.ahead + '개가 끝나면 시작합니다. (작업은 한 번에 하나씩, 만든 순서대로 처리합니다)';
    }
    return job.progress + '%' + (job.step ? ' · ' + job.step : '') + (job.message && job.status === 'RUNNING' ? ' · ' + job.message : '');
  }

  /** 0~1 비율 → "12.3%". 값이 없으면(계산 불가) "–" */
  function pct(v, digits) {
    return v == null || isNaN(v) ? '–' : (v * 100).toFixed(digits == null ? 1 : digits) + '%';
  }

  /** 숫자 → 고정 소수 자리. 값이 없으면 "–" */
  function fixed(v, digits) {
    return v == null || isNaN(v) ? '–' : Number(v).toFixed(digits == null ? 3 : digits);
  }

  /** epoch ms → KST "YYYY-MM-DD HH:mm" */
  function msToKst(ms) {
    return ms == null ? '' : toKst(new Date(ms).toISOString());
  }

  /** 상태 태그 (green · red · blue · 회색) */
  function tag(text, color) {
    return $('<span class="tag"><span class="dot"></span></span>').addClass(color ? 'tag-' + color : '').append(document.createTextNode(text));
  }

  return { api: api, toast: toast, formatNumber: formatNumber, toKst: toKst, pct: pct, fixed: fixed, msToKst: msToKst, tag: tag,
    cancelButton: cancelButton, jobText: jobText };
})(jQuery);
