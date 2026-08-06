FROM docker.elastic.co/elasticsearch/elasticsearch:8.15.0

RUN bin/elasticsearch-plugin install --batch https://release.infinilabs.com/analysis-ik/stable/elasticsearch-analysis-ik-8.15.0.zip
