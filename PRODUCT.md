# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users
Gleb, his family and guests, on one living-room TV (TCL 55 C6K, Google TV 14). Gleb sets it up; others
use it without explanation, so every action must be readable and predictable on first sight with a
standard TV remote (D-pad, OK, Back, Home).

## Product Purpose
A personal home screen built on a fork of CleanYourTV (`com.gathin.tvhome`): open the few apps the
household actually uses, switch to the PS5, see whether the VPN is on, and resume what was being watched,
with no ads or sponsored rows. Success: the right thing is one or two presses away, and the screen
stays fast on this TV.

## Operating Context
Watched in daytime and in the evening, from the sofa at TV distance (10-foot UI). Remote navigation only.
The TV is also a monitor for a PS5 on HDMI 1 (seen over HDMI-CEC). The VPN app is Happ
(`com.happproxy`); YouTube is the TizenTube client (`io.gh.reisxd.tizentube.cobalt`); the media centre is
Nuvio (`com.nuvio.tv`).

## Capabilities and Constraints
- Confirmed home apps: Happ, Nuvio, YouTube (TizenTube), VK Video, Kinopoisk. All other apps live in Apps.
- Planned: a centred top menu (Home / Apps / Settings), a PS5 button that switches to HDMI 1 over CEC, a VPN
  status that opens Happ, a Nuvio "Continue watching" row with real posters (dropped if Nuvio does not
  publish Watch Next rows).
- Must keep: HomeKeyService redirect from Google TV's Home with `key_filter=false`, the stock launcher and
  setupwraith enabled, native remote behaviour (Home, Settings, Input, voice, held volume).
- Performance: plain Java views, light animations kept, no video or blur; images decoded small.
- UI language: English.
- Undecided: project name, applicationId, separate repository; network access for posters.

## Product Principles
1. One or two presses to anything used daily.
2. Readable by a guest: labels over hidden gestures.
3. Status at a glance (VPN, PS5) without opening anything.
4. Fast before fancy on this TV.
