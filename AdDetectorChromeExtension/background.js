// Setup/method dependencies required to keep AdBlock 'filtering/*.js' code happy.

var _myfilters = new MyFilters();
_myfilters.init();

function get_custom_filters_text() { // dummy method, just to avoid code changes to 'filtering/*.js'.
  return void 0;
}

function get_settings() { // defaults are fine for our purposes.
  return {};
}

// Called when Chrome blocking needs to clear the in-memory cache.
// No-op for Safari.
function handlerBehaviorChanged() {
  if (SAFARI)
    return;
  try {
    chrome.webRequest.handlerBehaviorChanged();
  } catch (ex) {
  }
}

var STATS = {
  version: '2.7.10'
};

chrome.runtime.onMessage.addListener(function(request, sender, sendResponse) {
  if (request.action === 'identify_adverts') {
    
    var frameDomain = sender.url ? parseUri(sender.url).hostname : '';
    
    var elType = ElementTypes.fromOnBeforeRequestType(request.frame);
    
    var advertUrls = [];
    request.urls.forEach(function(url) {
      var blacklisted = _myfilters.blocking.matches(url, elType, frameDomain);
      
      if (blacklisted) {
        advertUrls.push(url);
      }
    });
    
    sendResponse(advertUrls);
  }
  else if (request.action === 'check_visibility') {
    // broadcast to all frames that they should check the visibility of tracked adverts.
    var payload = {
      action: "check_visibility"
    };
    chrome.tabs.sendMessage(sender.tab.id, payload);
  }
});