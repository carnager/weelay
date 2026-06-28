# -*- coding: utf-8 -*-
#
# ntfy_highlight.py — Send ntfy push notifications on WeeChat highlights
#
# Usage:
#   cp weechat_ntfy_highlight.py ~/.local/share/weechat/python/ntfy_highlight.py
#   /python load ntfy_highlight.py
#
# Settings (set via /set plugins.var.python.ntfy_highlight.*):
#   server_url  — ntfy server URL (default: https://ntfy.sh)
#   topic       — ntfy topic name; CHANGE THIS to something private/unguessable,
#                 since anyone who knows the topic can read your highlight contents
#   ignore_buffers — comma-separated buffer names to ignore (default: "")
#   min_interval — minimum seconds between notifications (default: 5)

import weechat
import json
import time
try:
    from urllib import quote as url_quote
except ImportError:
    from urllib.parse import quote as url_quote

SCRIPT_NAME = "ntfy_highlight"
SCRIPT_AUTHOR = "weelay"
SCRIPT_VERSION = "1.1"
SCRIPT_LICENSE = "GPL3"
SCRIPT_DESC = "Send ntfy notifications on highlights with Weelay deep link"

last_notification_time = 0

DEFAULTS = {
    "server_url": "https://ntfy.sh",
    "topic": "weechat-CHANGE-ME",
    "ignore_buffers": "",
    "min_interval": "5",
}


def config_get(key):
    return weechat.config_get_plugin(key)


def send_ntfy(topic, title, body, deep_link, tag):
    server_url = config_get("server_url") or DEFAULTS["server_url"]
    url = server_url.rstrip("/")

    payload = json.dumps({
        "topic": topic,
        "title": title,
        "message": body,
        "tags": [tag],
        "click": deep_link,
        "actions": [
            {
                "action": "view",
                "label": "Open in Weelay",
                "url": deep_link,
                "clear": True,
            }
        ],
    })

    hook = weechat.hook_process_hashtable(
        "url:{}".format(url),
        {
            "postfields": payload,
            "httpheader": "Content-Type: application/json",
        },
        10000,
        "notify_cb",
        "",
    )


def on_highlight(data, signal, signal_data):
    global last_notification_time

    now = time.time()
    min_interval = int(config_get("min_interval") or "5")
    if now - last_notification_time < min_interval:
        return weechat.WEECHAT_RC_OK

    msg = weechat.info_get_hashtable(
        "irc_message_parse", {"message": signal_data}
    )
    if not msg:
        return weechat.WEECHAT_RC_OK

    nick = msg.get("nick", "")
    channel = msg.get("channel", "")
    text = msg.get("text", "")
    network = signal.split(",")[0] if "," in signal else ""

    if not nick or not text:
        return weechat.WEECHAT_RC_OK

    ignore_buffers = [
        b.strip()
        for b in (config_get("ignore_buffers") or "").split(",")
        if b.strip()
    ]
    if channel in ignore_buffers:
        return weechat.WEECHAT_RC_OK

    buffer_full_name = "irc.{}.{}".format(network, channel) if network else channel
    topic = config_get("topic") or DEFAULTS["topic"]
    title = "{} in {}".format(nick, channel) if channel.startswith("#") else nick
    body = text if len(text) <= 300 else text[:297] + "..."
    deep_link = "weelay://chat/{}".format(url_quote(buffer_full_name, safe="."))

    send_ntfy(topic, title, body, deep_link, "speech_balloon")

    last_notification_time = now
    return weechat.WEECHAT_RC_OK


def on_privmsg(data, signal, signal_data):
    global last_notification_time

    now = time.time()
    min_interval = int(config_get("min_interval") or "5")
    if now - last_notification_time < min_interval:
        return weechat.WEECHAT_RC_OK

    msg = weechat.info_get_hashtable(
        "irc_message_parse", {"message": signal_data}
    )
    if not msg:
        return weechat.WEECHAT_RC_OK

    nick = msg.get("nick", "")
    channel = msg.get("channel", "")
    text = msg.get("text", "")
    network = signal.split(",")[0] if "," in signal else ""

    own_nick = weechat.info_get("irc_nick", network)
    if channel != own_nick:
        return weechat.WEECHAT_RC_OK

    if not nick or not text:
        return weechat.WEECHAT_RC_OK

    buffer_full_name = "irc.{}.{}".format(network, nick) if network else nick
    topic = config_get("topic") or DEFAULTS["topic"]
    title = "DM from {}".format(nick)
    body = text if len(text) <= 300 else text[:297] + "..."
    deep_link = "weelay://chat/{}".format(url_quote(buffer_full_name, safe="."))

    send_ntfy(topic, title, body, deep_link, "envelope")

    last_notification_time = now
    return weechat.WEECHAT_RC_OK


def notify_cb(data, command, return_code, out, err):
    if return_code != 0 and err:
        weechat.prnt("", "{}: error: {}".format(SCRIPT_NAME, err))
    return weechat.WEECHAT_RC_OK


if __name__ == "__main__":
    if weechat.register(
        SCRIPT_NAME, SCRIPT_AUTHOR, SCRIPT_VERSION,
        SCRIPT_LICENSE, SCRIPT_DESC, "", ""
    ):
        for key, default in DEFAULTS.items():
            if not weechat.config_is_set_plugin(key):
                weechat.config_set_plugin(key, default)

        weechat.hook_signal("*,irc_highlight", "on_highlight", "")
        weechat.hook_signal("*,irc_privmsg", "on_privmsg", "")

        weechat.prnt("", "{} {} loaded — notifications via {}".format(
            SCRIPT_NAME, SCRIPT_VERSION,
            config_get("server_url")
        ))
