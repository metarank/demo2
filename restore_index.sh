#!/bin/bash

curl -XPUT -H "Content-Type: application/json" -d '{"type": "fs", "settings": {"location": "/index"}}' http://localhost:9200/_snapshot/local

curl -XGET -v http://localhost:9200/_snapshot/local/esci-snapshot?pretty

