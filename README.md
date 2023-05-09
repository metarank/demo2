[![License: Apache 2](https://img.shields.io/badge/License-Apache2-green.svg)](https://opensource.org/licenses/Apache-2.0)
![Last commit](https://img.shields.io/github/last-commit/metarank/demo2)

# Metarank ESCI demo

This repo contains a docker-compose manifest for running the [Metarank](https://github.com/metarank/metarank) demo website
hosted on [demo.metarank.ai](https://demo.metarank.ai).

## Running demo locally

### Checking out the repo

Clone the whole repo:
```bash
git clone https://github.com/metarank/demo2.git
```

### State and index files

You need a set of data files to make it work smoothly:
* [state.db.gz](https://metarank-demo.s3.amazonaws.com/state.db.gz) (S3, 3Gb compressed) - Metarank state dump, contains all precomputed ranking feature values
for all documents.
* [index.tar.gz](https://metarank-demo.s3.amazonaws.com/index.tar.gz) (S3, 8Gb compressed) - A snapshot of ElasticSearch index for the ESCI dataset

After you download and uncompress the required data files, put them into the following positions:
```
.
├── app
├── docker-compose.yml
├── index                    // unpack the index here
│   ├── index-0
│   ├── index.latest
│   ├── indices
│   ├── meta-F935OzMqTbits8EHouCyEg.dat
│   └── snap-F935OzMqTbits8EHouCyEg.dat
├── indexer
├── metarank
│   ├── config.yml
│   ├── state
│   │   └── state.db         // unpack the state.db.gz here
│   ├── tf-bullets.json.gz
│   ├── tf-desc.json.gz
│   └── tf-title.json.gz
├── README.md
├── restore_index.sh

```

### Restoring the index

Run the included compose file:
```
docker compose up
```

This will expose the embedded Elasticsearch cluster on the port 9200. You need to do the following steps:
1. Create a local snapshot repository.
2. Validate that ES sees the snapshot we created.
3. Restore the snapshot to the cluster.

To simplify the restore, use the `restore_index.sh` script, which just does all the three steps at once:
```bash

$> curl -XPUT -H "Content-Type: application/json" -d '{"type": "fs", "settings": {"location": "/index"}}' http://localhost:9200/_snapshot/local

{"acknowledged": true}

$> curl -XGET -v http://localhost:9200/_snapshot/local/esci-snapshot?pretty

{
  "snapshots" : [
    {
      "snapshot" : "esci-snapshot",
      "uuid" : "F935OzMqTbits8EHouCyEg",
      "repository" : "local",
      "version_id" : 8060299,
      "version" : "8.6.2",
      "indices" : [
        ".geoip_databases",
        "esci"
      ],
      "data_streams" : [ ],
      "include_global_state" : true,
      "state" : "SUCCESS",
      "start_time" : "2023-05-08T16:54:36.501Z",
      "start_time_in_millis" : 1683564876501,
      "end_time" : "2023-05-08T16:59:57.025Z",
      "end_time_in_millis" : 1683565197025,
      "duration_in_millis" : 320524,
      "failures" : [ ],
      "shards" : {
        "total" : 2,
        "failed" : 0,
        "successful" : 2
      },
      "feature_states" : [
        {
          "feature_name" : "geoip",
          "indices" : [
            ".geoip_databases"
          ]
        }
      ]
    }
  ],
  "total" : 1,
  "remaining" : 0
}

$> curl -XPOST http://localhost:9200/_snapshot/local/esci-snapshot/_restore

{"accepted":true}

```

### Accessing the WEB UI

Go to the `http://localhost:8000` and it should work. If you encounter any problems, don't hesitate to open an issue in this repo.

### Required hardware

The demo is not tailored for a high RPS, so it uses a moderate amount of resources:
* Elasticsearch uses the `ES_JAVA_OPTS=-Xms1024m -Xmx1024m` option, but you're free to raise it higher to make ES search faster.
* Metarank has the `JAVA_OPTS=-Xmx1g` option and file-based state, so performance can depends on your disk IO - it will read document ranking features from disk on each request.


## Reindexing

If you're really curious, check out the `/indexer` repo for details how we build ES index. TLDR:
* `dense_vector` fields for `MiniLM-L6-v2` embeddings, with `cosine` distance
* we only store and search over `title`, `desc` and `bullets` fields

The mapping:

```json
{
  "esci" : {
    "aliases" : { },
    "mappings" : {
      "_source" : {
        "enabled" : false
      },
      "properties" : {
        "asin" : {
          "type" : "text",
          "index" : false,
          "store" : true
        },
        "bullets" : {
          "type" : "text",
          "store" : true,
          "analyzer" : "english"
        },
        "desc" : {
          "type" : "text",
          "store" : true,
          "analyzer" : "english"
        },
        "emb_minilm" : {
          "type" : "dense_vector",
          "dims" : 384,
          "index" : true,
          "similarity" : "cosine"
        },
        "emb_minilm_ft" : {
          "type" : "dense_vector",
          "dims" : 384,
          "index" : true,
          "similarity" : "cosine"
        },
        "image" : {
          "type" : "text",
          "index" : false,
          "store" : true
        },
        "title" : {
          "type" : "text",
          "store" : true,
          "analyzer" : "english"
        }
      }
    },
    "settings" : {
      "index" : {
        "routing" : {
          "allocation" : {
            "include" : {
              "_tier_preference" : "data_content"
            }
          }
        },
        "number_of_shards" : "1",
        "provided_name" : "esci",
        "creation_date" : "1683562219671",
        "number_of_replicas" : "1",
        "uuid" : "lZD_CW3bTZ-z0HrARDHHog",
        "version" : {
          "created" : "8060299"
        }
      }
    }
  }
}

```

## License

Apache 2.0