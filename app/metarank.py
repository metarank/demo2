import requests
import json

class Metarank():
	def __init__(self, endpoint: str):
		self.endpoint = endpoint

	def inferenceEncode(self, encoder: str, sentence: str) -> list[float]:
		request = {'texts': [sentence]}
		response = requests.post(self.endpoint  + '/inference/encoder/'+encoder, json=request)
		parsed = json.loads(response.content)
		embedding = parsed['embeddings'][0]
		return embedding

	def inferenceEncodeBatch(self, encoder: str, sentences: list[str]) -> list[list[float]]:
		request = {'texts': sentences}
		response = requests.post(self.endpoint  + '/inference/encoder/'+encoder, json=request)
		parsed = json.loads(response.content)
		embeddings = parsed['embeddings']
		return embeddings

	def rerank(self, name: str, query: str, docs: list[str]):
		items = [{'id': doc} for doc in docs]
		print(items)
		request = {'id': '1', 'timestamp': 0, 'items': items, 'fields': [{'name': 'query', 'value': query}]}
		response = requests.post(self.endpoint  + '/rank/'+name, json=request)
		return json.loads(response.content)
