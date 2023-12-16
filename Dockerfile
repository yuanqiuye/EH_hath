FROM openjdk:8-jdk-alpine as build
WORKDIR /source
COPY ./source/ ./
RUN apk --no-cache add findutils && \
    rm -rf /var/cache/apk/*
RUN chmod +x ./make.sh ./makejar.sh && ./make.sh && ./makejar.sh

FROM openjdk:8-jre-alpine as app

WORKDIR /hath
COPY --from=build /source/build/HentaiAtHome.jar ./
COPY ./hath_run.sh ./

VOLUME ["/hath/cache", "/hath/data", "/hath/download", "/hath/log", "/hath/tmp"]

CMD ["/hath/hath_run.sh"]
