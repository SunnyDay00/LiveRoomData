/*
 * DingTalk bot generic script.
 *
 * 用法：
 * 1) 直接运行 main() 发送默认消息
 * 2) callScript("DingTalkBot", "你的消息")
 * 3) callScript("DingTalkBot", {content:"xxx", webhook:"...", keyword:"...", atMobiles:["13800138000"], atAll:false, silent:true})
 */

var DING_WEBHOOK = "https://oapi.dingtalk.com/robot/send?access_token=e33de4f0dad6e497ec947d0de186628d86c2f352f475860671ef438df2bd7d77";
var DING_SECRET = ""; // 加签秘钥（不推荐在本引擎中使用）
var DING_KEYWORD = "数据采集通知："; // 关键词机器人必须包含
var DING_AT_MOBILES = []; // 可选: ["13800138000"]
var DING_AT_ALL = false;

function main(action, param1, param2, param3, param4, param5) {
  var opts = normalizeOptions(action, param1, param2, param3, param4, param5);
  if (opts.webhook == null || opts.webhook == "") {
    alert("Please set webhook.");
    return;
  }

  var ts = nowTimestamp();
  var url = buildWebhookUrl(opts.webhook, opts.secret, ts);
  var msg = buildMessage(opts.content, opts.keyword, ts);
  var payload = buildTextPayload(msg, opts.atMobiles, opts.atAll);

  var ret = httpPost(url, payload, "json");
  console.log("ding ret: " + ret);
  if (ret == null) {
    if (!opts.silent) {
      alert("httpPost returned null.");
    }
    return;
  }
  if (ret.state != "success") {
    if (!opts.silent) {
      alert("Request failed: " + ret.data);
    }
    return;
  }

  var err = parseDingErr(ret.data);
  if (err != null && err.code != 0) {
    if (!opts.silent) {
      alert("DingTalk error: " + err.code + " " + err.msg);
    }
    return;
  }

  if (!opts.silent) {
    alert("Send success: " + ret.data);
  }
}

function normalizeOptions(action, p1, p2, p3, p4, p5) {
  var opts = {
    webhook: DING_WEBHOOK,
    secret: DING_SECRET,
    keyword: DING_KEYWORD,
    content: "",
    atMobiles: DING_AT_MOBILES,
    atAll: DING_AT_ALL,
    silent: false
  };

  if (action != null && typeof action === "object") {
    var o = action;
    if (o.webhook != null) { opts.webhook = o.webhook; }
    if (o.secret != null) { opts.secret = o.secret; }
    if (o.keyword != null) { opts.keyword = o.keyword; }
    if (o.content != null) { opts.content = o.content; }
    if (o.atMobiles != null) { opts.atMobiles = o.atMobiles; }
    if (o.atAll != null) { opts.atAll = o.atAll; }
    if (o.silent == true) { opts.silent = true; }
    return opts;
  }

  if (action != null && action != "") {
    opts.content = action + "";
  }

  return opts;
}

function nowTimestamp() {
  var t = 0;
  try {
    t = new Date().getTime();
  } catch (e) {
    t = 0;
  }
  if (t == null || t == 0) {
    t = 0;
  }
  return t;
}

function buildWebhookUrl(webhook, secret, timestamp) {
  if (secret == null || secret == "") {
    return webhook;
  }
  if (timestamp == null || timestamp == 0) {
    timestamp = nowTimestamp();
  }
  var stringToSign = timestamp + "\n" + secret;
  var sign = hmacSha256Base64(stringToSign, secret);
  var signEncoded = urlEncode(sign);
  var sep = "&";
  if (webhook.indexOf("?") < 0) {
    sep = "?";
  }
  return webhook + sep + "timestamp=" + timestamp + "&sign=" + signEncoded;
}

function buildMessage(content, keyword, timestamp) {
  var msg = content;
  if (msg == null || msg == "") {
    msg = "DingTalk bot test " + timestamp;
  }
  if (keyword != null && keyword != "") {
    if (msg.indexOf(keyword) < 0) {
      msg = keyword + "\n\n" + msg;
    }
  }
  return msg;
}

function buildTextPayload(content, atMobiles, isAtAll) {
  var atObj = { atMobiles: [], isAtAll: false };
  if (atMobiles != null) {
    atObj.atMobiles = atMobiles;
  }
  if (isAtAll == true) {
    atObj.isAtAll = true;
  }

  var payload = {
    msgtype: "text",
    text: { content: content },
    at: atObj
  };
  return payload;
}

function parseDingErr(dataStr) {
  if (dataStr == null || dataStr == "") {
    return null;
  }
  var obj = null;
  try {
    obj = JSON.parse(dataStr);
  } catch (e) {
    obj = null;
  }
  if (obj == null) {
    return null;
  }
  var code = 0;
  var msg = "";
  if (obj.errcode != null) {
    code = obj.errcode;
  }
  if (obj.errmsg != null) {
    msg = obj.errmsg;
  }
  return { code: code, msg: msg };
}

function urlEncode(str) {
  if (str == null) {
    return "";
  }
  var s = str + "";
  var enc = "";
  var ok = false;

  try {
    if (typeof encodeURIComponent != "undefined") {
      enc = encodeURIComponent(s);
      ok = true;
    }
  } catch (e) {
  }

  if (ok) {
    return enc;
  }

  try {
    if (typeof encodeURI != "undefined") {
      enc = encodeURI(s);
    } else {
      enc = s;
    }
  } catch (e2) {
    enc = s;
  }

  enc = replaceAllStr(enc, "+", "%2B");
  enc = replaceAllStr(enc, "/", "%2F");
  enc = replaceAllStr(enc, "=", "%3D");
  return enc;
}

function replaceAllStr(s, from, to) {
  if (s == null) {
    return "";
  }
  var arr = (s + "").split(from);
  return arr.join(to);
}

function hmacSha256Base64(data, secret) {
  if (typeof rsContext == "undefined") {
    throw "rsContext is undefined";
  }

  var loader = rsContext.getClass().getClassLoader();
  var MacClass = loader.loadClass("javax.crypto.Mac");
  var SecretKeySpecClass = loader.loadClass("javax.crypto.spec.SecretKeySpec");
  var CharsetClass = loader.loadClass("java.nio.charset.Charset");

  var charset = CharsetClass.forName("UTF-8");
  var dataBytes = (data + "").getBytes(charset);
  var keyBytes = (secret + "").getBytes(charset);

  var keySpec = SecretKeySpecClass.newInstance(keyBytes, "HmacSHA256");
  var mac = MacClass.getInstance("HmacSHA256");
  mac.init(keySpec);
  var rawBytes = mac.doFinal(dataBytes);

  return base64Encode(rawBytes);
}

function base64Encode(bytes) {
  var loader = rsContext.getClass().getClassLoader();
  var Base64Class = null;
  var isAndroid = false;

  try {
    Base64Class = loader.loadClass("android.util.Base64");
    isAndroid = true;
  } catch (e1) {
    Base64Class = null;
  }

  if (Base64Class == null) {
    try {
      Base64Class = loader.loadClass("java.util.Base64");
      isAndroid = false;
    } catch (e2) {
      Base64Class = null;
    }
  }

  if (Base64Class == null) {
    throw "Base64 class not found";
  }

  if (isAndroid) {
    var NO_WRAP = Base64Class.NO_WRAP;
    return Base64Class.encodeToString(bytes, NO_WRAP);
  }

  var encoder = Base64Class.getEncoder();
  return encoder.encodeToString(bytes);
}
