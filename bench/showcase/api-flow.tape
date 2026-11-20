# vhs tape: the reservation API end to end. Rendered by bench/showcase/render.sh (real calls).
# The app runs with HOLD_TTL=PT15S: long enough for the first part, short enough to show expiry.
Output docs/showcase/api-flow.gif
Set Shell bash
Set FontSize 15
Set Width 1300
Set Height 760
Set Padding 18
Set Theme "Catppuccin Mocha"
Set TypingSpeed 12ms

Hide
Type "source bench/showcase/api.sh && export PS1='$ ' && clear"
Enter
Show

Type "E=$(new_event concert 1)   # an event with ONE ticket"
Enter
Sleep 600ms
Type "reserve $E alice key-1    # 201: held for payment (15 s in this demo)"
Enter
Sleep 1.5s
Type "A=$(last_id)"
Enter
Type "reserve $E alice key-1    # same key again: the same reservation, no second unit"
Enter
Sleep 1.5s
Type "reserve $E bob key-2      # 409: sold out"
Enter
Sleep 1.5s
Type "confirm $A                # payment callback: 200 CONFIRMED"
Enter
Sleep 2s
Type "E2=$(new_event matinee 1); reserve $E2 carol key-3"
Enter
Sleep 1.5s
Type "C=$(last_id); sleep 16     # wait past the 15 s hold (the wait is cut from the GIF)"
Enter
Hide
Sleep 16.5s
Show
Type "confirm $C                # payment too late: 410 Gone, the unit is back in stock"
Enter
Sleep 3s
