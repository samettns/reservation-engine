# Small shell helpers for the API demo (source it). Real HTTP calls against nginx on :8000.
API=${API:-http://localhost:8000}

# new_event <name> <stock>  -> prints the new event id
new_event() {
  curl -s -X POST "$API/events" -H 'Content-Type: application/json' \
    -d "{\"name\":\"$1\",\"stock\":$2}" | jq -r .id
}

# reserve <event> <user> <idempotency-key>
reserve() {
  curl -s -i -X POST "$API/events/$1/reserve" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $3" -d "{\"userId\":\"$2\"}" > /tmp/resp.txt
  show
}

# confirm <reservation-id>   (payment callback)
confirm() {
  curl -s -i -X POST "$API/reservations/$1/confirm" > /tmp/resp.txt
  show
}

# id of the reservation in the last response
last_id() { jq -r .reservationId /tmp/body.json; }

show() {
  tr -d '\r' < /tmp/resp.txt | sed -n '1p' | sed 's/^/  /'
  tr -d '\r' < /tmp/resp.txt | grep -iE '^(Idempotent-Replayed|Retry-After):' | sed 's/^/  /'
  tr -d '\r' < /tmp/resp.txt | sed '1,/^$/d' > /tmp/body.json
  jq -c 'if .reservationId then {reservationId, status, expiresAt} else {type, title, detail} end' /tmp/body.json \
    | sed 's/^/  /'
}
