from flask_bootstrap import Bootstrap5
from flask import Flask, render_template, request
from metarank import Metarank
from elasticsearch import Elasticsearch
import os, time, random

app = Flask(__name__)

bootstrap = Bootstrap5(app)

MR_HOST = os.getenv('MR_HOST')
metarank = Metarank(MR_HOST)

ES_HOST = os.getenv('ES_HOST')
es = Elasticsearch(hosts=ES_HOST)

example_queries = ["frying pan", "notebook", "wok", "walkie talkie"]

@app.route('/')
def index():
	return render_template('search.html', took={}, query=random.choice(example_queries), help=True)

@app.route('/search', methods=['GET'])
def search():
    query = request.args.get('query')
    method = request.args.get('retrieval')
    n = int(request.args.get('size'))
    rank = request.args.get('rank')
    start = time.time()
    docs = retrieve(method, query, n) 
    done1 = time.time()
    sorted = rerank(rank, query, docs['hits']['hits'])
    done2 = time.time()
    print(done1-start)
    return render_template('search.html', help=False, query=query, docs=sorted, method=method, rank=rank, size=n, took={"search": 1000*(done1-start), "rank": 1000*(done2-done1), "total": 1000*(done2-start)})


def retrieve(method: str, query: str, n: int):
    match method:
        case "bm25-all":
            return retrieve_bm25(query, ['title','desc','bullets'], n)
        case "bm25-title":
            return retrieve_bm25(query, ['title'], n)
        case vector:
            query_embedding = metarank.inferenceEncode(vector, query)
            field = "none"
            match vector:
                case "query_title_minilm": 
                    field = "emb_minilm"
                case "query_title_minilm_ft":
                    field = "emb_minilm_ft"
            return retrieve_vector(query_embedding, field, n)


def rerank(method: str, query: str, docs):
    docids = [doc['_id'] for doc in docs]
    result = metarank.rerank(method, query, docids)
    scores = {}
    for doc in result['items']:
        scores[doc['item']] = doc['score']
    s = sorted(docs, key=lambda x: scores[x['_id']], reverse=True)
    for doc in s:
        doc['_metarank'] = scores[doc['_id']]
    return s


def retrieve_bm25(qstr: str, fields: list[str], n: int):
    query={"multi_match": {"query": qstr, "fields": fields}}
    results = es.search(index='esci', query=query, size=n, stored_fields=['asin','title','image','desc','review_score'])
    return results

def retrieve_vector(embedding: list[float], field: str, n: int):
    query={"field": field, "query_vector": embedding, "k": n, "num_candidates": n}
    results = es.search(index='esci', knn=query, size=n, stored_fields=['asin','title','image','desc','review_score'])
    print(results)
    return results


if __name__ == '__main__':
	app.run(host='0.0.0.0', port=8000, debug=True)
