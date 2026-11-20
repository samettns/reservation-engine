# SVG -> PNG for the terminal screenshots (freeze writes SVG with the font embedded).
FROM alpine:3.20
RUN apk add --no-cache rsvg-convert fontconfig font-jetbrains-mono-nerd
