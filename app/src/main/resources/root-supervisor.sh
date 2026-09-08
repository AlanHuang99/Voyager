# Own one process group for supervision and a second for the requested command.
# stdin frames are acknowledged before the client may send another bounded frame.
set -f
set +m
umask 077
token=@TOKEN@
duration=@DURATION@
work=''
worker=''
supervisor=$$
for tool in setsid timeout mktemp mkfifo base64 stat; do
    command -v "$tool" >/dev/null 2>&1 || { echo "Root access requires $tool" >&2; exit 125; }
done
kill -0 "-$$" 2>/dev/null || { echo 'Root process-group isolation failed' >&2; exit 125; }
# Modern timeout creates groups unless --foreground is selected. Android 8 timeout has no such option and only signals its direct read child. Neither variant owns command cancellation: the supervisor explicitly signals the command group.
foreground=''
case "$(timeout --help 2>&1)" in *--foreground*) foreground='--foreground';; esac
read_frame() {
    timeout $foreground -k 1 "$duration" sh -c 'IFS= read -r frame || exit 1; printf "%s" "$frame"'
}
stop_worker() {
    if [ -n "$worker" ]; then
        kill -TERM "-$worker" "$worker" 2>/dev/null
        sleep 0.1
        kill -KILL "-$worker" "$worker" 2>/dev/null
    fi
}
finish() {
    trap '' TERM INT HUP USR1
    stop_worker
    if [ -n "$work" ]; then
        rm -f -- ./input
        rmdir -- "$work" 2>/dev/null
    fi
    printf '\n%s:DONE:%s\n' "$token" "$1" >&2
    kill -KILL 0
}
trap 'finish 125' TERM INT HUP USR1
work=$(mktemp -d @TEMP@/.voyager-shell-XXXXXXXX) || exit 125
cd "$work" || { rmdir -- "$work" 2>/dev/null; exit 125; }
# Android app cache directories may pass down setgid. It grants no group access.
case "$(stat -c '%u:%a' .)" in
    "$(id -u):700"|"$(id -u):2700") ;;
    *) echo 'Unsafe root command directory' >&2; finish 125;;
esac
mkfifo -m 600 ./input || finish 125
printf '\n%s:READY\n' "$token" >&2
frame=$(read_frame) || finish 125
[ "$frame" = START ] || finish 125
exec 7<&0
setsid sh -c @SCRIPT@ 7<&- < ./input &
worker=$!
(
    abort_command() {
        kill -TERM "-$worker" "$worker" 2>/dev/null
        kill -USR1 "$supervisor"
        exit 125
    }
    exec 8> ./input
    printf '%s\n' "$worker" >&8
    printf '\n%s:ACK\n' "$token" >&2
    while :; do
        frame=$(read_frame) || abort_command
        case "$frame" in
            D*)
                # Keep reading control frames while a bounded decoder is blocked.
                (
                    printf '%s' "${frame#D}" | base64 -d >&8 || abort_command
                    printf '\n%s:ACK\n' "$token" >&2
                ) &
                ;;
            PING) printf '\n%s:ACK\n' "$token" >&2;;
            FINISH) exec 8>&-; exit 0;;
            *) abort_command;;
        esac
    done
) <&7 &
wait "$worker"
status=$?
finish "$status"
