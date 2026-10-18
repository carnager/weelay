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
#   token       — ntfy access token for protected topics (sent as Bearer auth; default: "")
#   ignore_buffers — comma-separated buffer names to ignore (default: "")
#   min_interval — minimum seconds between notifications *per buffer* (default: 5)
#   icon_url    — URL of a PNG/JPEG shown beside the notification text (default: "")
#   priority_highlight — ntfy priority 1-5 for channel highlights (default: 4)
#   priority_dm — ntfy priority 1-5 for private messages (default: 5)
#   only_when_app_disconnected — "on" (default) to push only while no Weelay/api
#                 relay client is attached. Nothing to do with WeeChat's /away
#                 status: when the app holds a relay connection it raises its own
#                 notification, so pushing as well would notify you twice.

import weechat
import json
import time
try:
    from urllib import quote as url_quote
except ImportError:
    from urllib.parse import quote as url_quote

SCRIPT_NAME = "ntfy_highlight"
SCRIPT_AUTHOR = "weelay"
SCRIPT_VERSION = "1.2"
SCRIPT_LICENSE = "GPL3"
SCRIPT_DESC = "Send ntfy notifications on highlights with Weelay deep link"

# Last push time per target buffer. Throttling globally (as this once did) meant an unrelated
# channel highlight could silently swallow a direct message seconds later.
last_notification_time = {}

DEFAULTS = {
    "server_url": "https://ntfy.sh",
    "topic": "weechat-CHANGE-ME",
    "token": "",  # ntfy access token for protected topics (sent as Bearer auth)
    "ignore_buffers": "",
    "min_interval": "5",
    "icon_url": "",
    "priority_highlight": "4",
    "priority_dm": "5",
    "only_when_app_disconnected": "on",
}


def config_get(key):
    return weechat.config_get_plugin(key)


def relay_client_connected():
    """
    True while a relay client (i.e. Weelay) is attached over the api protocol.

    When the app is connected it raises its own notification for the same message, so pushing as
    well just means every highlight arrives twice. ntfy earns its keep when the app is *not*
    running, which is exactly when this returns False.
    """
    infolist = weechat.infolist_get("relay", "", "")
    if not infolist:
        return False
    connected = False
    while weechat.infolist_next(infolist):
        protocol = weechat.infolist_string(infolist, "protocol_string") or ""
        status = weechat.infolist_string(infolist, "status_string") or ""
        if protocol == "api" and status == "connected":
            connected = True
            break
    weechat.infolist_free(infolist)
    return connected


def send_ntfy(topic, title, body, deep_link, tag, priority):
    server_url = config_get("server_url") or DEFAULTS["server_url"]
    url = server_url.rstrip("/")

    message = {
        "topic": topic,
        "title": title,
        "message": body,
        "tags": [tag],
        # Priority drives whether Android shows a heads-up popup and how it alerts, which is the
        # one lever ntfy gives us to make a DM feel different from a channel mention.
        "priority": priority,
        "click": deep_link,
        "actions": [
            {
                "action": "view",
                "label": "Open in Weelay",
                "url": deep_link,
                "clear": True,
            }
        ],
    }

    icon_url = config_get("icon_url") or DEFAULTS["icon_url"]
    if icon_url:
        message["icon"] = icon_url

    payload = json.dumps(message)

    httpheader = "Content-Type: application/json"
    token = config_get("token") or DEFAULTS["token"]
    if token:
        # Use a private/auth-protected topic so highlight contents aren't world-readable.
        httpheader += "\nAuthorization: Bearer {}".format(token)

    hook = weechat.hook_process_hashtable(
        "url:{}".format(url),
        {
            "postfields": payload,
            "httpheader": httpheader,
        },
        10000,
        "notify_cb",
        "",
    )


def should_send(target):
    """Per-buffer rate limit plus the "only when the app isn't attached" gate."""
    if config_get("only_when_app_disconnected") != "off" and relay_client_connected():
        return False
    min_interval = int(config_get("min_interval") or "5")
    last = last_notification_time.get(target, 0)
    return (time.time() - last) >= min_interval


NICK_PREFIX_CHARS = "@+%~&!"


def on_print(data, buffer, date, tags, displayed, highlight, prefix, message):
    """
    Called for every printed line.

    hook_print is used rather than a signal because it is the only hook that reports the
    highlight flag, the line's tags and the buffer together — which is exactly what deciding
    "is this worth waking the phone for" needs. (The signals this script used to hook,
    irc_highlight and irc_privmsg, do not exist; the real ones deliver a display string that
    irc_message_parse cannot read.)
    """
    if not int(displayed or "0"):
        return weechat.WEECHAT_RC_OK

    tag_list = tags.split(",") if tags else []

    # Never notify for our own messages.
    if "self_msg" in tag_list:
        return weechat.WEECHAT_RC_OK

    # Only real chat lines. notify_none is WeeChat marking a line as explicitly not a message
    # (joins, backlog markers, mode changes), so it must be excluded rather than prefix-matched.
    if not any(t in tag_list for t in
               ("notify_message", "notify_private", "notify_highlight")):
        return weechat.WEECHAT_RC_OK

    full_name = weechat.buffer_get_string(buffer, "full_name")
    buf_type = weechat.buffer_get_string(buffer, "localvar_type")
    network = weechat.buffer_get_string(buffer, "localvar_server")
    short_name = weechat.buffer_get_string(buffer, "short_name") or full_name

    is_highlight = str(highlight) == "1"
    is_private = buf_type == "private"
    if not (is_highlight or is_private):
        return weechat.WEECHAT_RC_OK

    ignore_buffers = [
        b.strip()
        for b in (config_get("ignore_buffers") or "").split(",")
        if b.strip()
    ]
    if short_name in ignore_buffers or full_name in ignore_buffers:
        return weechat.WEECHAT_RC_OK

    # The prefix carries colour codes and an IRC mode char; neither belongs in a notification.
    nick = weechat.string_remove_color(prefix, "").strip().lstrip(NICK_PREFIX_CHARS)
    text = weechat.string_remove_color(message, "")
    if not text:
        return weechat.WEECHAT_RC_OK

    if not should_send(full_name):
        return weechat.WEECHAT_RC_OK

    topic = config_get("topic") or DEFAULTS["topic"]
    # ntfy renders the topic on its own line above the title, so the title has to carry the
    # identifying detail — who said it and where — or every push looks alike in the shade.
    if is_private:
        title = "{} · DM".format(nick or short_name)
        tag = "envelope"
        priority = int(config_get("priority_dm") or DEFAULTS["priority_dm"])
    else:
        title = "{} · {}".format(nick, short_name)
        tag = "speech_balloon"
        priority = int(config_get("priority_highlight") or DEFAULTS["priority_highlight"])
    if network:
        title += " ({})".format(network)

    body = text if len(text) <= 300 else text[:297] + "..."
    deep_link = "weelay://chat/{}".format(url_quote(full_name, safe="."))

    send_ntfy(topic, title, body, deep_link, tag, priority)

    last_notification_time[full_name] = time.time()
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

        # Fires for every printed line; on_print does the filtering.
        weechat.hook_print("", "", "", 1, "on_print", "")

        weechat.prnt("", "{} {} loaded — notifications via {}".format(
            SCRIPT_NAME, SCRIPT_VERSION,
            config_get("server_url")
        ))
