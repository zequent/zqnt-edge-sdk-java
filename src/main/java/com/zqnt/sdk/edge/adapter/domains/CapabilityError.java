package com.zqnt.sdk.edge.adapter.domains;

import lombok.*;

/** One error a command's execution can end in. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class CapabilityError {
	private String code;
	private String description;
}
