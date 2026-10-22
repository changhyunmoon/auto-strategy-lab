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

  return { api: api, toast: toast, formatNumber: formatNumber, toKst: toKst };
})(jQuery);
