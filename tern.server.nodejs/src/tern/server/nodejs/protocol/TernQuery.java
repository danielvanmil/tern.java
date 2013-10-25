package tern.server.nodejs.protocol;

import org.json.simple.JSONObject;

public class TernQuery extends JSONObject {

	public TernQuery(String type) {
		super.put("type", type);
	}

	public void setFile(String file) {
		super.put("file", file);
	}

	public void setPos(Integer pos) {
		super.put("pos", pos);
	}
}
